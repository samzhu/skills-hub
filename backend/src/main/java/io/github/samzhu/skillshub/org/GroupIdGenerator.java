package io.github.samzhu.skillshub.org;

import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

/**
 * Generates S170 group ids in the project short UID style: {@code g_<6 lowercase hex>}.
 */
@Component
public class GroupIdGenerator {

    private static final Pattern GROUP_ID_PATTERN = Pattern.compile("^g_[0-9a-f]{6}$");

    private final Supplier<String> idSupplier;

    /** Production constructor — UUID-derived candidate ids. */
    public GroupIdGenerator() {
        this(GroupIdGenerator::defaultGroupId);
    }

    /** Visible-for-test constructor — lets tests force collision candidates. */
    GroupIdGenerator(Supplier<String> idSupplier) {
        this.idSupplier = idSupplier;
    }

    String nextCandidate() {
        var candidate = idSupplier.get();
        if (!GROUP_ID_PATTERN.matcher(candidate).matches()) {
            throw new IllegalStateException("invalid_group_id_candidate: " + candidate);
        }
        return candidate;
    }

    private static String defaultGroupId() {
        return "g_" + UUID.randomUUID().toString().replace("-", "").substring(0, 6);
    }
}
