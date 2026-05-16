@org.springframework.modulith.ApplicationModule(
    // SearchProjection 仍透過 CurrentUserProvider / shared security 計算 query ACL scope。
    allowedDependencies = {"shared", "shared :: ai", "shared :: security", "skill :: domain"}
)
package io.github.samzhu.skillshub.search;
