/*
 * Copyright 2014-2019 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.data.repository.core.support;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.Optional;

import org.springframework.core.CollectionFactory;
import org.springframework.core.MethodParameter;
import org.springframework.core.convert.TypeDescriptor;
import org.springframework.core.convert.support.DefaultConversionService;
import org.springframework.core.convert.support.GenericConversionService;
import org.springframework.data.repository.util.NullableWrapper;
import org.springframework.data.repository.util.QueryExecutionConverters;
import org.springframework.data.repository.util.ReactiveWrapperConverters;
import org.springframework.lang.Nullable;

/**
 * Simple domain service to convert query results into a dedicated type.
 *
 * @author Oliver Gierke
 * @author Mark Paluch
 * @author Jens Schauder
 */
class QueryExecutionResultHandler {

	private static final TypeDescriptor WRAPPER_TYPE = TypeDescriptor.valueOf(NullableWrapper.class);

	private final GenericConversionService conversionService;
	private final ResultPostProcessorInvoker invoker;

	public QueryExecutionResultHandler() {
		this(ResultPostProcessorInvoker.NONE);
	}

	/**
	 * Creates a new {@link QueryExecutionResultHandler}.
	 */
	public QueryExecutionResultHandler(ResultPostProcessorInvoker invoker) {

		GenericConversionService conversionService = new DefaultConversionService();
		QueryExecutionConverters.registerConvertersIn(conversionService);
		conversionService.removeConvertible(Object.class, Object.class);

		this.conversionService = conversionService;
		this.invoker = invoker;
	}

	/**
	 * Post-processes the given result of a query invocation to match the return type of the given method.
	 *
	 * @param result can be {@literal null}.
	 * @param method must not be {@literal null}.
	 * @return
	 */
	@Nullable
	public Object postProcessInvocationResult(@Nullable Object result, Method method) {

		Object processedResult = invoker.postProcess(result);
		MethodParameter parameter = new MethodParameter(method, -1);

		return postProcessInvocationResult(processedResult, 0, parameter);
	}

	/**
	 * Post-processes the given result of a query invocation to the given type.
	 *
	 * @param result can be {@literal null}.
	 * @param nestingLevel
	 * @param parameter must not be {@literal null}.
	 * @return
	 */
	@Nullable
	Object postProcessInvocationResult(@Nullable Object result, int nestingLevel, MethodParameter parameter) {

		TypeDescriptor returnTypeDescriptor = TypeDescriptor.nested(parameter, nestingLevel);

		if (returnTypeDescriptor == null) {
			return result;
		}

		Class<?> expectedReturnType = returnTypeDescriptor.getType();

		result = unwrapOptional(result);

		if (QueryExecutionConverters.supports(expectedReturnType)) {

			// For a wrapper type, try nested resolution first
			result = postProcessInvocationResult(result, nestingLevel + 1, parameter);

			TypeDescriptor targetType = TypeDescriptor.valueOf(expectedReturnType);

			if (conversionService.canConvert(WRAPPER_TYPE, returnTypeDescriptor)
					&& !conversionService.canBypassConvert(WRAPPER_TYPE, targetType)) {

				return conversionService.convert(new NullableWrapper(result), expectedReturnType);
			}

			if (result != null
					&& conversionService.canConvert(TypeDescriptor.valueOf(result.getClass()), returnTypeDescriptor)
					&& !conversionService.canBypassConvert(TypeDescriptor.valueOf(result.getClass()), targetType)) {

				return conversionService.convert(result, expectedReturnType);
			}
		}

		if (result != null) {

			if (ReactiveWrapperConverters.supports(expectedReturnType)) {
				return ReactiveWrapperConverters.toWrapper(result, expectedReturnType);
			}

			return conversionService.canConvert(TypeDescriptor.forObject(result), returnTypeDescriptor)
					? conversionService.convert(result, returnTypeDescriptor)
					: result;
		}

		return Map.class.equals(expectedReturnType) //
				? CollectionFactory.createMap(expectedReturnType, 0) //
				: null;
	}

	/**
	 * Unwraps the given value if it's a JDK 8 {@link Optional}.
	 *
	 * @param source can be {@literal null}.
	 * @return
	 */
	@Nullable
	@SuppressWarnings("unchecked")
	static Object unwrapOptional(@Nullable Object source) {

		return source == null //
				? null //
				: Optional.class.isInstance(source) ? Optional.class.cast(source).orElse(null) : source;
	}
}
