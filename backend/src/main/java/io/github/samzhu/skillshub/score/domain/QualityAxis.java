package io.github.samzhu.skillshub.score.domain;

/** S135a: 3-axis quality evaluation — stored as VARCHAR(20) via Spring Data JDBC enum-to-name convention. */
public enum QualityAxis {
    VALIDATION, IMPLEMENTATION, ACTIVATION
}
