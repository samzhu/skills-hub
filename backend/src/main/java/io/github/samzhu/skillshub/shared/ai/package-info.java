/**
 * S171: Spring AI manual wiring boundary.
 *
 * <p>Provider-specific builders live here; other modules consume Spring AI interfaces via
 * beans and may only reference this package through the {@code shared :: ai} Modulith boundary.
 */
@org.springframework.modulith.NamedInterface("ai")
package io.github.samzhu.skillshub.shared.ai;
