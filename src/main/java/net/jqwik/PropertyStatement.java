/*
 The MIT License

 Copyright (c) 2010-2016 Paul R. Holser, Jr.

 Permission is hereby granted, free of charge, to any person obtaining
 a copy of this software and associated documentation files (the
 "Software"), to deal in the Software without restriction, including
 without limitation the rights to use, copy, modify, merge, publish,
 distribute, sublicense, and/or sell copies of the Software, and to
 permit persons to whom the Software is furnished to do so, subject to
 the following conditions:

 The above copyright notice and this permission notice shall be
 included in all copies or substantial portions of the Software.

 THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE
 LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION
 OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION
 WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
*/

package net.jqwik;

import static java.util.stream.Collectors.toList;

import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;
import com.pholser.junit.quickcheck.Property;
import com.pholser.junit.quickcheck.internal.GeometricDistribution;
import com.pholser.junit.quickcheck.internal.ParameterTypeContext;
import com.pholser.junit.quickcheck.internal.PropertyParameterContext;
import com.pholser.junit.quickcheck.internal.ShrinkControl;
import com.pholser.junit.quickcheck.internal.generator.GeneratorRepository;
import com.pholser.junit.quickcheck.internal.generator.PropertyParameterGenerationContext;
import com.pholser.junit.quickcheck.random.SourceOfRandomness;
import org.opentest4j.AssertionFailedError;
import org.slf4j.Logger;
import net.jqwik.api.AssumptionViolatedException;
import ru.vyarus.java.generics.resolver.GenericsResolver;

class PropertyStatement implements Statement {
	private final Method method;
	private final Class<?> testClass;
	private final GeneratorRepository repo;
	private final GeometricDistribution distro;
	private final Logger seedLog;
	private final List<AssumptionViolatedException> assumptionViolations = new ArrayList<>();
	private int successes;

	PropertyStatement(Method method, Class<?> testClass, GeneratorRepository repo, GeometricDistribution distro,
			Logger seedLog) {

		this.method = method;
		this.testClass = testClass;
		this.repo = repo;
		this.distro = distro;
		this.seedLog = seedLog;
	}

	@Override
	public void evaluate() throws Throwable {
		Property marker = method.getAnnotation(Property.class);
		int trials = marker.trials();
		ShrinkControl shrinkControl = new ShrinkControl(marker.shrink(), marker.maxShrinks(), marker.maxShrinkDepth(),
			marker.maxShrinkTime());

		List<PropertyParameterGenerationContext> params = parameters(trials);

		for (int i = 0; i < trials; ++i)
			verifyProperty(params, shrinkControl);

		if (successes == 0 && !assumptionViolations.isEmpty()) {
			throw new AssertionFailedError(
				"No values satisfied property assumptions. Violated assumptions: " + assumptionViolations);
		}
	}

	public boolean hasAcceptedReturnType() {
		return isAcceptedPropertyReturnType(method.getReturnType());
	}

	private boolean isAcceptedPropertyReturnType(Class<?> propertyReturnType) {
		return propertyReturnType.equals(boolean.class) || propertyReturnType.equals(Boolean.class)
				|| propertyReturnType.equals(void.class);
	}

	private void verifyProperty(List<PropertyParameterGenerationContext> params, ShrinkControl shrinkControl)
			throws Throwable {

		Object[] args = argumentsFor(params);
		property(params, args, shrinkControl).verify();
	}

	private PropertyVerifier property(List<PropertyParameterGenerationContext> params, Object[] args,
			ShrinkControl shrinkControl) {

		return new PropertyVerifier(testClass, method, args, s -> ++successes, assumptionViolations::add, e -> {
			if (!shrinkControl.shouldShrink())
				throw e;

			try {
				shrink(params, args, shrinkControl, e);
			}
			catch (AssertionError ex) {
				throw ex;
			}
			catch (Throwable ex) {
				throw new AssertionError(ex.getCause());
			}
		});
	}

	private void shrink(List<PropertyParameterGenerationContext> params, Object[] args, ShrinkControl shrinkControl,
			AssertionError failure) throws Throwable {

		new Shrinker(method, testClass, failure, shrinkControl.maxShrinks(), shrinkControl.maxShrinkDepth(),
			shrinkControl.maxShrinkTime()).shrink(params, args);
	}

	private List<PropertyParameterGenerationContext> parameters(int trials) {
		Map<String, Type> typeVariables = GenericsResolver.resolve(testClass).method(method).genericsMap();

		return Arrays.stream(method.getParameters()).map(p -> parameterContextFor(p, trials, typeVariables)).map(
			p -> new PropertyParameterGenerationContext(p, repo, distro, new SourceOfRandomness(new Random()),
				seedLog)).collect(toList());
	}

	private PropertyParameterContext parameterContextFor(Parameter parameter, int trials,
			Map<String, Type> typeVariables) {

		return new PropertyParameterContext(new ParameterTypeContext(parameter.getName(), parameter.getAnnotatedType(),
			declarerName(parameter), typeVariables).allowMixedTypes(true), trials).annotate(parameter);
	}

	private static String declarerName(Parameter p) {
		Executable exec = p.getDeclaringExecutable();
		return exec.getDeclaringClass().getName() + '.' + exec.getName();
	}

	private Object[] argumentsFor(List<PropertyParameterGenerationContext> params) {
		return params.stream().map(PropertyParameterGenerationContext::generate).collect(toList()).toArray();
	}
}