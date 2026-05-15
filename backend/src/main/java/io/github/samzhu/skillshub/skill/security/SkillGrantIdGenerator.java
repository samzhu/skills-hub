package io.github.samzhu.skillshub.skill.security;

import java.security.SecureRandom;

import org.springframework.stereotype.Component;

/**
 * S177 — short stable grant ids for public visibility mirror grants.
 */
@Component
public class SkillGrantIdGenerator {

    private final SecureRandom random = new SecureRandom();

    public String nextId() {
        var bytes = new byte[6];
        random.nextBytes(bytes);
        var sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
