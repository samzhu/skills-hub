package io.github.samzhu.skillshub.shared.api;

/**
 * S096f2-T02 — Collection id 不存在。GlobalExceptionHandler → 404 + {@code error: "collection_not_found"}。
 */
public class CollectionNotFoundException extends RuntimeException {
    public CollectionNotFoundException(String collectionId) {
        super("collection_not_found: " + collectionId);
    }
}
