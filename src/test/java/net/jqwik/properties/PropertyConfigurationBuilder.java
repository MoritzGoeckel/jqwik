package net.jqwik.properties;

import net.jqwik.api.*;
import net.jqwik.descriptor.*;

class PropertyConfigurationBuilder {

	static PropertyConfigurationBuilder aConfig() {
		return new PropertyConfigurationBuilder();
	}

	private String seed = "1000";
	private int tries = 100;
	private int maxDiscardRatio = 5;
	private ShrinkingMode shrinkingMode = ShrinkingMode.FULL;
	private GenerationMode generationMode = GenerationMode.AUTO;

	PropertyConfigurationBuilder withSeed(String seed) {
		this.seed = seed;
		return this;
	}

	PropertyConfigurationBuilder withTries(int tries) {
		this.tries = tries;
		return this;
	}

	PropertyConfigurationBuilder withMaxDiscardRatio(int maxDiscardRatio) {
		this.maxDiscardRatio = maxDiscardRatio;
		return this;
	}

	PropertyConfigurationBuilder withShriking(ShrinkingMode shrinkingMode) {
		this.shrinkingMode = shrinkingMode;
		return this;
	}

	PropertyConfigurationBuilder withGeneration(GenerationMode generationMode) {
		this.generationMode = generationMode;
		return this;
	}

	PropertyConfiguration build() {
		return new PropertyConfiguration(
			"Property",
			seed,
			tries,
			maxDiscardRatio,
			shrinkingMode,
			generationMode
		);

	}

}
