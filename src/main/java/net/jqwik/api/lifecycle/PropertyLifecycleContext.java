package net.jqwik.api.lifecycle;

import java.lang.reflect.Method;

/**
 * Experimental feature. Not ready for public usage yet.
 */
public interface PropertyLifecycleContext {

	Method targetMethod();

	Class containerClass();

	String label();

	Object testInstance();

}
