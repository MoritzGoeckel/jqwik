package net.jqwik.api.providers;

import java.lang.annotation.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.util.stream.*;

import net.jqwik.*;
import net.jqwik.api.*;
import net.jqwik.support.*;

/**
 * An instance of {@code TypeUsage} describes the information available for parameter or return types.
 * The class is supposed to relieve its users from all the intricacies of the Java reflection API.
 * Doing that it will resolve meta annotations, repeated annotations as well as annotated type parameters.
 * <p>
 * {@code TypeUsage} provides access to:
 * <ul>
 * <li>the native type of an object</li>
 * <li>the component type (if it's an array)</li>
 * <li>the type parameters (again as instances of {@code TypeUsage})</li>
 * <li>the annotations (if the object is derived from a parameter)</li>
 * <li>methods to test for compatibility of types that do also handle compatibility
 * between raw types and boxed type</li>
 * </ul>
 * <p>
 * Within the public API {@code TypeUsage} is used in two places:
 * <ul>
 * <li>@see {@link ArbitraryProvider}</li>
 * <li>@see {@link Arbitraries#defaultFor(Class, Class[])}</li>
 * </ul>
 */
public class TypeUsage {

	private static Map<Type, TypeUsage> resolved = new ConcurrentHashMap<>();

	private static final String WILDCARD = "?";

	public static TypeUsage of(Class<?> type, TypeUsage... typeParameters) {
		if (typeParameters.length > 0 && typeParameters.length != type.getTypeParameters().length) {
			String typeArgumentsString = JqwikStringSupport.displayString(typeParameters);
			throw new JqwikException(String.format("Type [%s] cannot have type parameters [%s]", type, typeArgumentsString));
		}
		TypeUsage typeUsage = new TypeUsage(type, null, Collections.emptyList());
		typeUsage.addTypeArguments(Arrays.asList(typeParameters));
		return typeUsage;
	}

	public static TypeUsage forParameter(MethodParameter parameter) {
		TypeUsage typeUsage = new TypeUsage(
			extractRawType(parameter.getType()),
			extractTypeVariable(parameter.getType()),
			parameter.findAllAnnotations()
		);
		typeUsage.addTypeArguments(extractTypeArguments(parameter));
		typeUsage.addUpperBounds(extractUpperBounds(parameter.getType()));
		typeUsage.addLowerBounds(extractLowerBounds(parameter.getType()));

		return typeUsage;
	}

	public static TypeUsage wildcard(TypeUsage upperBound) {
		TypeUsage typeUsage = new TypeUsage(Object.class, WILDCARD, Collections.emptyList());
		typeUsage.addUpperBounds(Arrays.asList(upperBound));
		return typeUsage;
	}

	public static TypeUsage forType(Type type) {
		TypeUsage typeUsage;
		if (type instanceof WildcardType) {
			typeUsage = TypeUsage.forWildcard((WildcardType) type);
		} else {
			typeUsage = TypeUsage.forParameterizedType(type);
		}
		return typeUsage;
	}

	private static TypeUsage forParameterizedType(Type parameterizedType) {
		return resolveOrCreate(
			parameterizedType,
			extractRawType(parameterizedType),
			extractTypeVariable(parameterizedType),
			Collections.emptyList(),
			typeUsage -> {
				typeUsage.addTypeArguments(extractPlainTypeArguments(parameterizedType));
				typeUsage.addUpperBounds(extractUpperBounds(parameterizedType));
				typeUsage.addLowerBounds(extractLowerBounds(parameterizedType));
			}
		);
	}

	private static TypeUsage forWildcard(WildcardType wildcardType) {
		return resolveOrCreate(
			wildcardType,
			Object.class,
			WILDCARD,
			extractAnnotations(wildcardType),
			typeUsage -> {
				typeUsage.addUpperBounds(extractUpperBounds(wildcardType));
				typeUsage.addLowerBounds(extractLowerBounds(wildcardType));
			}
		);
	}

	private static TypeUsage resolveOrCreate(
		Type type,
		Class rawType,
		String typeVariable,
		List<Annotation> annotations,
		Consumer<TypeUsage> processTypeUsage
	) {
		Optional<TypeUsage> alreadyResolved = alreadyResolvedIn(type);
		if (alreadyResolved.isPresent()) {
			return alreadyResolved.get();
		}

		TypeUsage typeUsage = new TypeUsage(rawType, typeVariable, annotations);
		resolved.put(type, typeUsage);
		processTypeUsage.accept(typeUsage);

		return typeUsage;
	}

	private static TypeUsage forAnnotatedType(AnnotatedType annotatedType) {
		TypeUsage typeUsage = new TypeUsage(
			extractRawType(annotatedType.getType()),
			extractTypeVariable(annotatedType.getType()),
			extractAnnotations(annotatedType)
		);
		typeUsage.addTypeArguments(extractPlainTypeArguments(annotatedType));
		typeUsage.addUpperBounds(extractUpperBounds(annotatedType.getType()));
		typeUsage.addLowerBounds(extractLowerBounds(annotatedType.getType()));
		return typeUsage;
	}

	private static Optional<TypeUsage> alreadyResolvedIn(Type type) {
		return Optional.ofNullable(resolved.get(type));
	}


	private final Class<?> rawType;
	private final String typeVariable;
	private final List<Annotation> annotations;
	private final List<TypeUsage> typeArguments = new ArrayList<>();
	private final List<TypeUsage> upperBounds = new ArrayList<>();
	private final List<TypeUsage> lowerBounds = new ArrayList<>();

	private TypeUsage(Class<?> rawType, String typeVariable, List<Annotation> annotations) {
		this.rawType = rawType;
		this.typeVariable = typeVariable;
		this.annotations = annotations;
	}

	private void addTypeArguments(List<TypeUsage> typeArguments) {
		this.typeArguments.addAll(typeArguments);
	}

	private void addLowerBounds(List<TypeUsage> lowerBounds) {
		this.lowerBounds.addAll(lowerBounds);
	}

	private void addUpperBounds(List<TypeUsage> upperBounds) {
		this.upperBounds.addAll(upperBounds);
	}

	private static List<TypeUsage> extractTypeArguments(MethodParameter parameter) {
		if (parameter.isAnnotatedParameterized()) {
			return extractAnnotatedTypeArguments(parameter.getAnnotatedType());
		} else {
			return extractPlainTypeArguments(parameter.getType());
		}
	}

	private static List<Annotation> extractAnnotations(Object parameterizedType) {
		if (parameterizedType instanceof AnnotatedElement)
			return JqwikAnnotationSupport.findAllAnnotations((AnnotatedElement) parameterizedType);
		return Collections.emptyList();
	}

	private static String extractTypeVariable(Type parameterizedType) {
		if (parameterizedType instanceof WildcardType) {
			return WILDCARD;
		}
		if (parameterizedType instanceof TypeVariable) {
			return ((TypeVariable) parameterizedType).getName();
		}
		return null;
	}

	private static List<TypeUsage> extractUpperBounds(Type parameterizedType) {
		if (parameterizedType instanceof TypeVariable) {
			return Arrays.stream(((TypeVariable) parameterizedType).getBounds())
						 .map(TypeUsage::forType)
						 .collect(Collectors.toList());
		}
		if (parameterizedType instanceof WildcardType) {
			return extractUpperBounds((WildcardType) parameterizedType);
		}
		return new ArrayList<>();
	}

	private static List<TypeUsage> extractUpperBounds(WildcardType wildcardType) {
		return Arrays.stream(wildcardType.getUpperBounds())
					 .map(TypeUsage::forType)
					 .collect(Collectors.toList());
	}

	private static List<TypeUsage> extractLowerBounds(Type parameterizedType) {
		if (parameterizedType instanceof WildcardType) {
			return extractLowerBounds((WildcardType) parameterizedType);
		}
		return Collections.emptyList();
	}

	private static List<TypeUsage> extractLowerBounds(WildcardType wildcardType) {
		return Arrays.stream(wildcardType.getLowerBounds())
					 .map(TypeUsage::forType)
					 .collect(Collectors.toList());
	}

	private static Class<?> extractRawType(Type parameterizedType) {
		if (parameterizedType instanceof Class) {
			return (Class) parameterizedType;
		}
		if (parameterizedType instanceof ParameterizedType) {
			return (Class) ((ParameterizedType) parameterizedType).getRawType();
		}
		// Now we have a type variable (java.lang.reflect.TypeVariable)
		return Object.class;
	}

	private static List<TypeUsage> extractPlainTypeArguments(Object parameterizedType) {
		if (parameterizedType instanceof AnnotatedParameterizedType) {
			return extractAnnotatedTypeArguments((AnnotatedParameterizedType) parameterizedType);
		}
		if (parameterizedType instanceof ParameterizedType) {
			return Arrays.stream(((ParameterizedType) parameterizedType).getActualTypeArguments()) //
						 .map(TypeUsage::forType) //
						 .collect(Collectors.toList());
		}
		// Now it's either not a generic type or it has type variables
		return Collections.emptyList();
	}

	private static List<TypeUsage> extractAnnotatedTypeArguments(AnnotatedParameterizedType annotatedType) {
		return Arrays.stream(annotatedType.getAnnotatedActualTypeArguments()) //
					 .map(TypeUsage::forAnnotatedType) //
					 .collect(Collectors.toList());
	}

	/**
	 * Return the raw type which is usually the class or interface you see in a parameters or return values
	 * specification.
	 * <p>
	 * A raw type always exists.
	 */
	public Class<?> getRawType() {
		return rawType;
	}

	/**
	 * Return true if a type parameter has upper bounds.
	 */
	public boolean hasUpperBounds() {
		if (upperBounds.size() > 1)
			return true;
		return upperBounds.size() == 1 && !upperBounds.get(0).isOfType(Object.class);
	}

	/**
	 * Return true if a type parameter has upper bounds.
	 */
	public boolean hasLowerBounds() {
		return lowerBounds.size() > 0;
	}

	/**
	 * Return true if a generic type is a wildcard.
	 */
	public boolean isWildcard() {
		return typeVariable != null && typeVariable.equals(WILDCARD);
	}

	/**
	 * Return true if a generic type is a wildcard.
	 */
	public boolean isTypeVariable() {
		return typeVariable != null && !isWildcard();
	}

	/**
	 * Return true if a generic type is a type variable or a wildcard.
	 */
	public boolean isTypeVariableOrWildcard() {
		return isWildcard() || isTypeVariable();
	}

	/**
	 * Return the type arguments of a generic type in the order of there appearance in a type's declaration.
	 */
	public List<TypeUsage> getTypeArguments() {
		return typeArguments;
	}

	/**
	 * Check if an instance is of a specific raw type
	 * <p>
	 * Most of the time this is what you want to do when checking for applicability of a
	 * {@linkplain ArbitraryProvider}.
	 */
	public boolean isOfType(Class<?> aRawType) {
		if (isTypeVariableOrWildcard())
			return false;
		return rawType == aRawType;
	}

	/**
	 * Check if an instance can be assigned to another {@code TypeUsage} instance.
	 */
	public boolean canBeAssignedTo(TypeUsage targetType) {
		if (targetType.isTypeVariableOrWildcard()) {
			return canBeAssignedToUpperBounds(targetType) && canBeAssignedToLowerBounds(targetType);
		}
		if (boxedTypeMatches(targetType.rawType, this.rawType))
			return true;
		if (boxedTypeMatches(this.rawType, targetType.rawType))
			return true;
		if (targetType.getRawType().isAssignableFrom(rawType)) {
			if (allTypeArgumentsCanBeAssigned(this.getTypeArguments(), targetType.getTypeArguments())) {
				return true;
			} else {
				// TODO: This is too loose since it potentially allows not matching types
				// which will lead to class cast exception during property execution
				return findSuperType(targetType.rawType).isPresent();
			}
		}
		return false;
	}

	private boolean canBeAssignedToUpperBounds(TypeUsage targetType) {
		if (isTypeVariableOrWildcard()) {
			return upperBounds.stream().allMatch(upperBound -> upperBound.canBeAssignedToUpperBounds(targetType));
		}
		return targetType.upperBounds.stream().allMatch(this::canBeAssignedTo);
	}

	private boolean canBeAssignedToLowerBounds(TypeUsage targetType) {
		if (isTypeVariableOrWildcard()) {
			return lowerBounds.stream().allMatch(lowerBound -> lowerBound.canBeAssignedToLowerBounds(targetType));
		}
		return targetType.lowerBounds.stream().allMatch(lowerBound -> lowerBound.canBeAssignedTo(this));
	}

	private boolean allTypeArgumentsCanBeAssigned(
		List<TypeUsage> providedTypeArguments, List<TypeUsage> targetTypeArguments
	) {
		if (providedTypeArguments.size() == 0) {
			return true;
		}
		if (targetTypeArguments.size() == 0) {
			return true;
		}
		if (targetTypeArguments.size() != providedTypeArguments.size())
			return false;
		for (int i = 0; i < targetTypeArguments.size(); i++) {
			TypeUsage providedTypeArgument = providedTypeArguments.get(i);
			TypeUsage targetTypeArgument = targetTypeArguments.get(i);
			if (!providedTypeArgument.canBeAssignedTo(targetTypeArgument))
				return false;
		}
		return true;
	}

	/**
	 * Return true if a type has any type arguments itself.
	 */
	public boolean isGeneric() {
		return typeArguments.size() > 0;
	}

	/**
	 * Return true if a type is an {@code enum} type.
	 */
	public boolean isEnum() {
		return getRawType().isEnum();
	}

	/**
	 * Return true if a type is an array type.
	 */
	public boolean isArray() {
		return getRawType().isArray();
	}

	/**
	 * Return all annotations of a parameter (or an annotated type argument).
	 * <p>
	 * This list already contains all meta annotations, repeated annotations and annotations
	 * from annotated type arguments. Thus, it does much more than the usual Java reflection API.
	 */
	public List<Annotation> getAnnotations() {
		return annotations;
	}

	/**
	 * Return an {@code Optional} of the first instance of a specific {@code annotationType} if there is one.
	 *
	 * @deprecated Use {@link #findAnnotation(Class)} instead.
	 */
	@Deprecated
	public <A extends Annotation> Optional<A> getAnnotation(Class<A> annotationType) {
		return findAnnotation(annotationType);
	}

	/**
	 * Return an {@code Optional} of the first instance of a specific {@code annotationType}
	 * if there is one (directly or indirectly through meta-annotations).
	 */
	public <A extends Annotation> Optional<A> findAnnotation(Class<A> annotationType) {
		return annotations.stream()
						  .filter(annotation -> annotation.annotationType().equals(annotationType))
						  .map(annotationType::cast)
						  .findFirst();
	}

	/**
	 * Return true if the current instance is annotated (directly or indirectly through meta-annotations)
	 * with a specific {@code annotationType}.
	 */
	public <A extends Annotation> boolean isAnnotated(Class<A> annotationType) {
		return findAnnotation(annotationType).isPresent();
	}

	/**
	 * Check if a given {@code providedClass} is assignable from this generic type.
	 */
	public boolean isAssignableFrom(Class<?> providedClass) {
		return TypeUsage.of(providedClass).canBeAssignedTo(this);
	}

	/**
	 * Return an {@code Optional} of an array's component type - if it is an array.
	 */
	public Optional<TypeUsage> getComponentType() {
		Class<?> componentType = rawType.getComponentType();
		if (componentType != null)
			return Optional.of(TypeUsage.of(componentType));
		return Optional.empty();
	}

	private boolean boxedTypeMatches(Class<?> providedType, Class<?> targetType) {
		if (providedType.equals(Long.class) && targetType.equals(long.class))
			return true;
		if (providedType.equals(Integer.class) && targetType.equals(int.class))
			return true;
		if (providedType.equals(Short.class) && targetType.equals(short.class))
			return true;
		if (providedType.equals(Byte.class) && targetType.equals(byte.class))
			return true;
		if (providedType.equals(Character.class) && targetType.equals(char.class))
			return true;
		if (providedType.equals(Double.class) && targetType.equals(double.class))
			return true;
		if (providedType.equals(Float.class) && targetType.equals(float.class))
			return true;
		return providedType.equals(Boolean.class) && targetType.equals(boolean.class);
	}

	public Optional<TypeUsage> findSuperType(Class<?> typeToFind) {
		return findSuperTypeIn(typeToFind, this.rawType);
	}

	private Optional<TypeUsage> findSuperTypeIn(Class<?> typeToFind, Class<?> rawType) {
		List<AnnotatedType> supertypes = new ArrayList<>();
		if (rawType.getSuperclass() != null)
			supertypes.add(rawType.getAnnotatedSuperclass());
		supertypes.addAll(Arrays.asList(rawType.getAnnotatedInterfaces()));
		for (AnnotatedType type : supertypes) {
			if (extractRawType(type.getType()).equals(typeToFind))
				return Optional.of(TypeUsage.forAnnotatedType(type));
		}

		for (AnnotatedType type : supertypes) {
			TypeUsage typeUsage = TypeUsage.forAnnotatedType(type);
			Optional<TypeUsage> nestedFound = typeUsage.findSuperType(typeToFind);
			if (nestedFound.isPresent())
				return nestedFound;
		}

		return Optional.empty();
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj.getClass() != getClass())
			return false;
		TypeUsage other = (TypeUsage) obj;
		if (!other.getRawType().equals(getRawType()))
			return false;
		if (!other.getTypeArguments().equals(getTypeArguments()))
			return false;
		if (!other.getAnnotations().equals(getAnnotations()))
			return false;
		if (other.isWildcard() && isWildcard()) {
			if (!(other.lowerBounds.equals(lowerBounds)))
				return false;
			if (!(other.upperBounds.equals(upperBounds)))
				return false;
		}
		if (other.isTypeVariable() && isTypeVariable()) {
			if (!other.typeVariable.equals(typeVariable))
				return false;
			return (other.upperBounds.equals(upperBounds));
		}
		return true;
	}

	@Override
	public int hashCode() {
		return getRawType().hashCode();
	}

	@Override
	public String toString() {
		return toString(new HashSet<>());
	}

	// TODO: Clean up
	public String toString(Set<TypeUsage> touchedTypes) {
		if (touchedTypes.contains(this)) {
			if (isTypeVariableOrWildcard()) {
				return typeVariable;
			}
			return "";
		}
		touchedTypes.add(this);

		String representation = getRawType().getSimpleName();
		if (isGeneric()) {
			String typeArgsRepresentation = typeArguments
												.stream()
												.map(typeUsage -> typeUsage.toString(touchedTypes))
												.collect(Collectors.joining(", "));
			representation = String.format("%s<%s>", representation, typeArgsRepresentation);
		}
		if (isArray()) {
			representation = String.format("%s[]", getComponentType().get().toString(touchedTypes));
		}
		if (typeVariable != null) {
			representation = typeVariable;
			if (hasUpperBounds()) {
				String boundsRepresentation =
					upperBounds.stream()
							   .map(typeUsage -> typeUsage.toString(touchedTypes))
							   .collect(Collectors.joining(" & "));
				representation += String.format(" extends %s", boundsRepresentation);
			}
			if (hasLowerBounds()) {
				String boundsRepresentation =
					lowerBounds.stream() //
							   .map(typeUsage -> typeUsage.toString(touchedTypes)) //
							   .collect(Collectors.joining(" & "));
				representation += String.format(" super %s", boundsRepresentation);
			}
		}
		if (!annotations.isEmpty()) {
			String annotationRepresentation = annotations.stream()
														 .map(Annotation::toString)
														 .collect(Collectors.joining(" "));
			representation = annotationRepresentation + " " + representation;
		}
		return representation;
	}

}
