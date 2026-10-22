package com.uber.nullaway.oracles;

import com.google.common.collect.ImmutableList;

/**
 * A set of compilation units the pilot compiles together, taken from one chain of {@code
 * addSourceLines} calls in a NullAway test.
 *
 * @param name where the seed came from: the test file, the test method, and the chain's line
 * @param sources the compilation units, in the order the test added them
 */
record Seed(String name, ImmutableList<Source> sources) {}
