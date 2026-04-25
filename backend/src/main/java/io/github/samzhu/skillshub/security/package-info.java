@org.springframework.modulith.ApplicationModule(
    allowedDependencies = {"shared :: events", "shared :: api", "skill :: domain", "storage"}
)
package io.github.samzhu.skillshub.security;
