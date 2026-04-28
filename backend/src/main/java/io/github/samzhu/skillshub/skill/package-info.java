@org.springframework.modulith.ApplicationModule(
    // S016: 加 "shared :: security" — skill/security/SkillPermissionStrategy import
    // PermissionStrategy / CurrentUserProvider / AclPrincipalExpander 三個 shared::security 元件
    allowedDependencies = {"shared :: events", "shared :: api", "shared :: security", "storage"}
)
package io.github.samzhu.skillshub.skill;
