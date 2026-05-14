@org.springframework.modulith.ApplicationModule(
    // S014 T7: SearchProjection 兩步驟寫入需注入 CurrentUserProvider 取得 owner（補
    // vector_store.owner 欄位；S016/S017 ACL 鋪路）— 加 "shared :: security" allow。
    allowedDependencies = {"shared", "shared :: ai", "shared :: security", "skill :: domain"}
)
package io.github.samzhu.skillshub.search;
