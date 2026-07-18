/*
 * This file is part of ViaBedrock - https://github.com/RaphiMC/ViaBedrock
 * Copyright (C) 2023-2026 RK_01/RaphiMC and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package net.raphimc.viabedrock.experimental.resourcepack.cache;

import net.raphimc.viabedrock.api.resourcepack.ResourcePack;
import net.raphimc.viabedrock.api.resourcepack.cache.ContentDigest;
import net.raphimc.viabedrock.api.resourcepack.content.Content;
import net.raphimc.viabedrock.api.resourcepack.content.FrozenContent;
import net.raphimc.viabedrock.api.resourcepack.content.PackContentView;
import net.raphimc.viabedrock.api.resourcepack.content.ZipFileContent;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * One immutable resource-pack file tree interned by its exact content digest.
 *
 * <p>Production entries are opened only through {@link ResourcePackArchiveStore} and are backed by a
 * strongly verified canonical CAS ZIP. The compatibility factory exists solely for embedders and tests
 * that construct {@link SharedPackRuntimeCache} without a store; it must not be treated as production
 * shared-cache evidence.</p>
 */
public final class FrozenPackBlob {

    public enum BackingKind {
        CANONICAL_CAS,
        COMPATIBILITY
    }

    private final ContentDigest contentDigest;
    private final ResourcePack resourcePack;
    private final PackContentView content;
    private final Path canonicalPath;
    private final BackingKind backingKind;
    private final long estimatedHeapWeightBytes;

    private FrozenPackBlob(final ContentDigest contentDigest, final ResourcePack resourcePack,
                           final Path canonicalPath, final BackingKind backingKind,
                           final long estimatedHeapWeightBytes) {
        this.contentDigest = Objects.requireNonNull(contentDigest, "contentDigest");
        this.resourcePack = Objects.requireNonNull(resourcePack, "resourcePack");
        this.content = resourcePack.content();
        this.canonicalPath = canonicalPath;
        this.backingKind = Objects.requireNonNull(backingKind, "backingKind");
        this.estimatedHeapWeightBytes = Math.max(1L, estimatedHeapWeightBytes);
    }

    static FrozenPackBlob canonical(final ContentDigest expectedDigest, final ResourcePack resourcePack,
                                    final Path canonicalPath, final long estimatedHeapWeightBytes) {
        Objects.requireNonNull(expectedDigest, "expectedDigest");
        final Path normalizedPath = Objects.requireNonNull(canonicalPath, "canonicalPath")
                .toAbsolutePath().normalize();
        if (!normalizedPath.getFileName().toString().equals(expectedDigest.hex() + ".zip")) {
            throw new IllegalArgumentException("Canonical resource pack path does not match its content digest");
        }
        return new FrozenPackBlob(expectedDigest, resourcePack, normalizedPath, BackingKind.CANONICAL_CAS,
                saturatingAdd(estimatedHeapWeightBytes, 512L));
    }

    static FrozenPackBlob compatibility(final ContentDigest expectedDigest, final ResourcePack source) {
        Objects.requireNonNull(expectedDigest, "expectedDigest");
        Objects.requireNonNull(source, "source");
        // A ZipFileContent object is read-only, but its external path can still be replaced in place.
        // The compatibility cache has no CAS lease, so only an already-owned FrozenContent is reusable.
        final Content immutableContent = source.content() instanceof FrozenContent
                ? source.content() : new FrozenContent(source.content());

        final ContentDigest actualDigest = ContentDigest.compute(immutableContent);
        if (!expectedDigest.equals(actualDigest)) {
            throw new IllegalArgumentException("Compatibility resource pack content digest mismatch: "
                    + actualDigest + " != " + expectedDigest);
        }
        final ResourcePack immutablePack = immutableContent == source.content()
                ? source : new ResourcePack(immutableContent);
        if (!source.key().equals(immutablePack.key())) {
            throw new IllegalArgumentException("Compatibility resource pack manifest identity changed while freezing");
        }
        return new FrozenPackBlob(expectedDigest, immutablePack, null, BackingKind.COMPATIBILITY,
                saturatingAdd(estimateContentWeight(immutableContent), 512L));
    }

    public ContentDigest contentDigest() {
        return this.contentDigest;
    }

    public ResourcePack.Key manifestKey() {
        return this.resourcePack.key();
    }

    public String name() {
        return this.resourcePack.name();
    }

    public PackContentView content() {
        return this.content;
    }

    public Optional<Path> canonicalPath() {
        return Optional.ofNullable(this.canonicalPath);
    }

    public BackingKind backingKind() {
        return this.backingKind;
    }

    public boolean isProductionCanonical() {
        return this.backingKind == BackingKind.CANONICAL_CAS;
    }

    public long estimatedHeapWeightBytes() {
        return this.estimatedHeapWeightBytes;
    }

    ResourcePack resourcePack() {
        return this.resourcePack;
    }

    private static long estimateContentWeight(final Content content) {
        if (content instanceof FrozenContent frozenContent) {
            return frozenContent.weightBytes();
        }
        if (content instanceof ZipFileContent zipFileContent) {
            return zipFileContent.weightBytes();
        }
        throw new IllegalArgumentException("Frozen pack blob content is not immutable");
    }

    private static long saturatingAdd(final long left, final long right) {
        return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }

}
