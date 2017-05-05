package net.jqwik.properties.shrinking;

import java.util.*;
import java.util.function.*;

public class ShrinkableChoice<T> implements Shrinkable<T> {

	public static <T> ShrinkableChoice<T> empty() {
		return new ShrinkableChoice<>();
	}

	private final List<Shrinkable<T>> choices = new ArrayList<>();

	public void addChoice(Shrinkable<T> sequence) {
		choices.add(sequence);
	}

	public List<Shrinkable<T>> choices() {
		return choices;
	}

	public Optional<ShrinkResult<T>> shrink(Predicate<T> falsifier) {
		return choices.stream() //
			.map(choice -> choice.shrink(falsifier)) //
			.filter(Optional::isPresent) //
			.map(Optional::get) //
			.sorted(Comparator.naturalOrder()) //
			.findFirst();
	}

}
