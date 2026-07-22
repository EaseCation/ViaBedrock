/*
 * This file is part of ViaBedrock - https://github.com/RaphiMC/ViaBedrock
 * Copyright (C) 2023-2026 RK_01/RaphiMC and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package net.raphimc.viabedrock.protocol.storage;

import com.viaversion.viaversion.api.Via;
import com.viaversion.viaversion.api.connection.StoredObject;
import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.protocol.packet.PacketWrapper;
import com.viaversion.viaversion.api.type.Types;
import net.raphimc.viabedrock.ViaBedrock;
import net.raphimc.viabedrock.api.resourcepack.ResourcePack;
import net.raphimc.viabedrock.api.resourcepack.cache.PackAlias;
import net.raphimc.viabedrock.api.resourcepack.content.ZipContent;
import net.raphimc.viabedrock.api.resourcepack.http.BedrockPackDownloader;
import net.raphimc.viabedrock.api.resourcepack.http.RemotePackServiceClient;
import net.raphimc.viabedrock.protocol.BedrockProtocol;
import net.raphimc.viabedrock.protocol.ServerboundBedrockPackets;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ResourcePackResponse;
import net.raphimc.viabedrock.protocol.provider.ResourcePackProvider;
import net.raphimc.viabedrock.protocol.types.BedrockTypes;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

public class ResourcePackLoadStateTracker extends StoredObject {

    private final Map<ResourcePack.Key, Info> requests = new HashMap<>();
    private final Map<UUID, List<Info>> requestsById = new HashMap<>();
    private final Map<ResourcePack.Key, PackAlias> aliases = new HashMap<>();
    private final Map<ResourcePack.Key, ResourcePack> resourcePacks = new ConcurrentHashMap<>();
    private final AtomicInteger remainingResourcePackCount = new AtomicInteger();
    private final CompletableFuture<Void> loadFuture = new CompletableFuture<>();
    private final String announcementSequenceFingerprint;
    private UUID httpToken;
    private volatile RemotePackServiceClient.Lookup remotePackLookup;
    private CompletableFuture<RemotePackServiceClient.Lookup> remotePackLookupFuture;
    private boolean javaClientAccepted;

    public ResourcePackLoadStateTracker(final UserConnection user, final ResourcePackLoadStateTracker.Info[] infos) {
        this(user, infos, AnnouncementHeader.compatibility(), backendScope(user));
    }

    public ResourcePackLoadStateTracker(final UserConnection user, final ResourcePackLoadStateTracker.Info[] infos,
                                        final AnnouncementHeader announcementHeader) {
        this(user, infos, announcementHeader, backendScope(user));
    }

    ResourcePackLoadStateTracker(final UserConnection user, final ResourcePackLoadStateTracker.Info[] infos,
                                 final AnnouncementHeader announcementHeader, final String backendScope) {
        super(user);
        Objects.requireNonNull(infos, "infos");
        Objects.requireNonNull(announcementHeader, "announcementHeader");
        Objects.requireNonNull(backendScope, "backendScope");
        this.announcementSequenceFingerprint = fingerprintAnnouncementSequence(announcementHeader, infos);
        for (Info info : infos) {
            final Info previous = this.requests.putIfAbsent(info.key(), info);
            if (previous != null && !previous.hasSameAnnouncement(info)) {
                throw new IllegalArgumentException(
                        "Conflicting RESOURCE_PACKS_INFO declarations for resource pack " + info.key());
            }
            if (previous == null) {
                this.requestsById.computeIfAbsent(info.key().id(), ignored -> new ArrayList<>()).add(info);
            }
            this.aliases.put(info.key(), info.alias(backendScope));
        }
        this.remainingResourcePackCount.set(this.requests.size());
    }

    public Info getRequest(final ResourcePack.Key key) {
        return this.requests.get(key);
    }

    /** Resolves the protocol's optional-version transfer name to one announced resource pack identity. */
    public ResolvedRequest resolveTransferRequest(final String transferName) {
        Objects.requireNonNull(transferName, "transferName");
        final int versionSeparator = transferName.indexOf('_');
        if (versionSeparator >= 0) {
            final ResourcePack.Key key = ResourcePack.Key.fromString(transferName);
            final Info info = this.requests.get(key);
            if (info == null) {
                throw new IllegalArgumentException("Unannounced resource pack transfer: " + transferName);
            }
            return new ResolvedRequest(key, info);
        }

        final UUID id;
        try {
            id = UUID.fromString(transferName);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid resource pack transfer name: " + transferName, e);
        }
        final List<Info> candidates = this.requestsById.getOrDefault(id, List.of());
        if (candidates.isEmpty()) {
            throw new IllegalArgumentException("Unannounced resource pack transfer: " + transferName);
        }
        if (candidates.size() != 1) {
            throw new IllegalArgumentException("Ambiguous versionless resource pack transfer " + transferName
                    + " matches " + candidates.size() + " announced versions");
        }
        final Info info = candidates.getFirst();
        return new ResolvedRequest(info.key(), info);
    }

    public PackAlias getAlias(final ResourcePack.Key key) {
        return this.aliases.get(key);
    }

    public String announcementSequenceFingerprint() {
        return this.announcementSequenceFingerprint;
    }

    public void addRemoteResourcePack(final ResourcePack resourcePack) {
        try {
            Via.getManager().getProviders().get(ResourcePackProvider.class).save(resourcePack);
        } catch (Throwable e) {
            ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Failed to save resource pack: " + resourcePack.key(), e);
        }
        this.addLocalResourcePack(resourcePack);
    }

    public void addLocalResourcePack(final ResourcePack resourcePack) {
        if (this.resourcePacks.putIfAbsent(resourcePack.key(), resourcePack) != null) {
            return;
        }
        if (this.remainingResourcePackCount.decrementAndGet() == 0) {
            ViaBedrock.getPlatform().getLogger().log(Level.INFO, "All resource packs have been loaded");
            this.loadFuture.complete(null);
        }
    }

    public ResourcePack getResourcePack(final ResourcePack.Key key) {
        return this.resourcePacks.get(key);
    }

    public CompletableFuture<Void> loadRequestedResourcePacks() {
        final ResourcePackDownloadTracker connectionTracker = this.user().get(ResourcePackDownloadTracker.class);
        if (connectionTracker == null) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Resource pack download tracker is unavailable"));
        }
        final CompletableFuture<Void> sessionLoadFuture = this.loadFuture;
        connectionTracker.trackConnectionStage(sessionLoadFuture);
        if (sessionLoadFuture.isDone()) {
            return sessionLoadFuture;
        }

        final WeakReference<ResourcePackLoadStateTracker> trackerReference = new WeakReference<>(this);
        final List<CompletableFuture<Void>> asyncTasks = new ArrayList<>();
        final List<ResourcePack.Key> downloadList = Collections.synchronizedList(new ArrayList<>());
        final boolean sharedCache = ViaBedrock.isSharedResourcePackCacheEnabled();
        final ResourcePackProvider resourcePackProvider =
                Via.getManager().getProviders().get(ResourcePackProvider.class);
        for (Info info : this.requests.values()) {
            if (this.mayUseBundledResourcePack(info.key(), sharedCache)
                    && BedrockProtocol.MAPPINGS.getBedrockResourcePacks().containsKey(info.key())) {
                this.addLocalResourcePack(BedrockProtocol.MAPPINGS.getBedrockResourcePacks().get(info.key()));
            } else if (!sharedCache && resourcePackProvider.has(info.key())) {
                asyncTasks.add(ViaBedrock.getResourcePackWorkScheduler().runIo(() -> {
                    final ResourcePackLoadStateTracker tracker = trackerReference.get();
                    if (!isConnectionActive(tracker)) {
                        return;
                    }
                    try {
                        tracker.addLocalResourcePack(resourcePackProvider.load(info.key()));
                    } catch (Throwable e) {
                        if (!(e.getCause() instanceof InterruptedException)) {
                            ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Failed to load resource pack: " + info.key(), e);
                            downloadList.add(info.key());
                        }
                    }
                }));
            } else if (info.httpUrl() != null) {
                if (sharedCache) {
                    final PackAlias alias = this.getAlias(info.key());
                    final boolean trustAlias = ViaBedrock.getConfig().shouldTrustDeclaredPackAlias();
                    final CompletableFuture<ResourcePack> sharedSource =
                            ViaBedrock.getResourcePackArchiveStore().loadFromStreamSource(
                                    info.httpUrl().toExternalForm(), alias, this.announcementSequenceFingerprint,
                                    trustAlias, info.contentKey(), output -> {
                                final BedrockPackDownloader downloader = new BedrockPackDownloader(info.httpUrl());
                                final long maxBytes = (long) ViaBedrock.getConfig().getResourcePackMaxArchiveMiB()
                                        * 1024L * 1024L;
                                final long actualSize = downloader.downloadTo(output, maxBytes);
                                validateCdnSize(info.announcedSize(), actualSize);
                            });
                    final CompletableFuture<ResourcePack> connectionSource = detachedWaiter(sharedSource);
                    connectionTracker.trackConnectionStage(connectionSource);
                    asyncTasks.add(connectionSource.thenAccept(pack -> {
                        final ResourcePackLoadStateTracker tracker = trackerReference.get();
                        if (isConnectionActive(tracker)) {
                            tracker.addLocalResourcePack(pack);
                        }
                    }).exceptionally(e -> {
                        recordCdnFailure(e, info.key(), downloadList);
                        return null;
                    }));
                } else {
                    final CompletableFuture<Void> legacyDownload =
                            ViaBedrock.getResourcePackWorkScheduler().runIo(() -> {
                                try {
                                    final BedrockPackDownloader downloader = new BedrockPackDownloader(info.httpUrl());
                                    final long maxBytes = (long) ViaBedrock.getConfig().getResourcePackMaxArchiveMiB()
                                            * 1024L * 1024L;
                                    final byte[] archive = downloader.download(maxBytes);
                                    validateCdnSize(info.announcedSize(), archive.length);
                                    final ResourcePack pack = new ResourcePack(new ZipContent(archive));
                                    final ResourcePackLoadStateTracker tracker = trackerReference.get();
                                    if (isConnectionActive(tracker)) {
                                        tracker.addRemoteResourcePack(pack);
                                    }
                                } catch (Throwable e) {
                                    recordCdnFailure(e, info.key(), downloadList);
                                }
                            });
                    connectionTracker.trackConnectionStage(legacyDownload);
                    asyncTasks.add(legacyDownload.exceptionally(e -> null));
                }
            } else {
                downloadList.add(info.key());
            }
        }
        CompletableFuture.allOf(asyncTasks.toArray(CompletableFuture[]::new))
                .orTimeout(ViaBedrock.getConfig().getResourcePackCacheBuildTimeoutSeconds(), TimeUnit.SECONDS)
                .thenRun(() -> {
            final ResourcePackLoadStateTracker tracker = trackerReference.get();
            if (!isConnectionActive(tracker)) {
                sessionLoadFuture.completeExceptionally(
                        new CancellationException("Resource pack connection closed while loading packs"));
                return;
            }
            if (!downloadList.isEmpty()) {
                ViaBedrock.getPlatform().getLogger().log(Level.INFO, "Downloading " + downloadList.size() + " resource packs over the game protocol");
                final PacketWrapper resourcePackClientResponse = PacketWrapper.create(
                        ServerboundBedrockPackets.RESOURCE_PACK_CLIENT_RESPONSE, tracker.user());
                resourcePackClientResponse.write(Types.BYTE, (byte) ResourcePackResponse.Downloading.getValue()); // status
                resourcePackClientResponse.write(BedrockTypes.SHORT_LE_STRING_ARRAY, downloadList.stream().map(ResourcePack.Key::toString).toArray(String[]::new)); // downloading packs
                resourcePackClientResponse.scheduleSendToServer(BedrockProtocol.class);
            } else {
                sessionLoadFuture.complete(null);
            }
        }).exceptionally(e -> {
            sessionLoadFuture.completeExceptionally(e);
            return null;
        });
        return sessionLoadFuture.orTimeout(
                ViaBedrock.getConfig().getResourcePackCacheBuildTimeoutSeconds(), TimeUnit.SECONDS);
    }

    static <T> CompletableFuture<T> detachedWaiter(final CompletionStage<T> sharedSource) {
        final CompletableFuture<T> dependent = new CompletableFuture<>();
        sharedSource.whenComplete((value, error) -> {
            if (error == null) {
                dependent.complete(value);
            } else {
                dependent.completeExceptionally(error);
            }
        });
        return dependent;
    }

    private static boolean isConnectionActive(final ResourcePackLoadStateTracker tracker) {
        return tracker != null && tracker.user().getChannel().isActive();
    }

    private static Throwable unwrap(final Throwable error) {
        Throwable current = error;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static void recordCdnFailure(final Throwable error, final ResourcePack.Key key,
                                         final List<ResourcePack.Key> downloadList) {
        final Throwable cause = unwrap(error);
        if (cause instanceof CancellationException || cause instanceof InterruptedException) {
            return;
        }
        ViaBedrock.getPlatform().getLogger().log(
                Level.WARNING, "Failed to download resource pack: " + key, error);
        downloadList.add(key);
    }

    static void validateCdnSize(final long announcedSize, final long actualSize) throws IOException {
        if (announcedSize >= 0L && actualSize != announcedSize) {
            throw new IOException("CDN resource pack size changed during acquisition: "
                    + actualSize + " != " + announcedSize);
        }
    }

    public void loadUnrequestedResourcePacks(final ResourcePack.Key[] keys) {
        final boolean sharedCache = ViaBedrock.isSharedResourcePackCacheEnabled();
        final ResourcePackProvider resourcePackProvider =
                Via.getManager().getProviders().get(ResourcePackProvider.class);
        for (ResourcePack.Key key : keys) {
            if (!this.mayUseBundledResourcePack(key, sharedCache)) {
                continue;
            }
            if (BedrockProtocol.MAPPINGS.getBedrockResourcePacks().containsKey(key)) {
                this.resourcePacks.put(key, BedrockProtocol.MAPPINGS.getBedrockResourcePacks().get(key));
            } else if (!sharedCache && resourcePackProvider.has(key)) {
                try {
                    this.resourcePacks.put(key, resourcePackProvider.load(key));
                } catch (Throwable e) {
                    ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Failed to load resource pack: " + key, e);
                }
            }
        }
    }

    boolean mayUseBundledResourcePack(final ResourcePack.Key key, final boolean sharedCache) {
        // A stack-only built-in is local input; an INFO declaration with the same key is untrusted input.
        return !sharedCache || !this.requests.containsKey(key);
    }

    public CompletableFuture<PreparedStack> prepareResourcePackStackAsync(final ResourcePack.Key[] keys,
                                                                           final String[] selectedSubpacks) {
        final StackPreparationExecutor executor;
        if (ViaBedrock.getResourcePackWorkScheduler() != null) {
            executor = ViaBedrock.getResourcePackWorkScheduler()::submitIo;
        } else {
            executor = ResourcePackLoadStateTracker::submitViaWorker;
        }
        return this.prepareResourcePackStackAsync(keys, selectedSubpacks, executor);
    }

    CompletableFuture<PreparedStack> prepareResourcePackStackAsync(final ResourcePack.Key[] keys,
                                                                    final String[] selectedSubpacks,
                                                                    final StackPreparationExecutor executor) {
        if (keys.length != selectedSubpacks.length) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("Resource pack and subpack stack sizes differ"));
        }
        final ResourcePack.Key[] keysSnapshot = keys.clone();
        final String[] subpacksSnapshot = selectedSubpacks.clone();
        try {
            return executor.submit(() -> this.prepareResourcePackStack(keysSnapshot, subpacksSnapshot));
        } catch (Throwable e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    PreparedStack prepareResourcePackStack(final ResourcePack.Key[] keys, final String[] selectedSubpacks) {
        this.loadUnrequestedResourcePacks(keys);
        final List<ResourcePack> resourcePacks = new ArrayList<>(keys.length);
        final List<String> resolvedSubpacks = new ArrayList<>(keys.length);
        final List<ResourcePack> decryptedLegacyPacks = new ArrayList<>();
        final boolean sharedCache = ViaBedrock.isSharedResourcePackCacheEnabled();
        for (int i = 0; i < keys.length; i++) {
            final ResourcePack.Key key = keys[i];
            final ResourcePack resourcePack = this.getResourcePack(key);
            if (resourcePack == null) {
                if (sharedCache) {
                    throw new IllegalStateException("Missing resource pack required by stack: " + key);
                }
                ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Missing resource pack: " + key);
                continue;
            }
            if (sharedCache && !this.requests.containsKey(key)
                    && !BedrockProtocol.MAPPINGS.getBedrockResourcePacks().containsKey(key)) {
                throw new IllegalStateException(
                        "Unannounced resource pack cannot enter a shared stack: " + key);
            }

            final Info info = this.getRequest(key);
            if (resourcePack.isContentEncrypted()) {
                if (sharedCache) {
                    throw new IllegalStateException(
                            "Shared CAS resource pack remained encrypted after verification: " + key);
                }
                if (info != null && info.contentKey().length > 0) {
                    resourcePack.decryptContent(info.contentKey(), info.contentId());
                    decryptedLegacyPacks.add(resourcePack);
                }
            }
            resourcePacks.add(resourcePack);
            resolvedSubpacks.add(selectedSubpacks[i]);
        }

        if (!decryptedLegacyPacks.isEmpty()) {
            final ResourcePackProvider provider = Via.getManager().getProviders().get(ResourcePackProvider.class);
            for (ResourcePack resourcePack : decryptedLegacyPacks) {
                try {
                    provider.save(resourcePack);
                } catch (Throwable e) {
                    ViaBedrock.getPlatform().getLogger().log(
                            Level.WARNING, "Failed to save resource pack: " + resourcePack.key(), e);
                }
            }
        }
        return new PreparedStack(resourcePacks, resolvedSubpacks);
    }

    private static <T> CompletableFuture<T> submitViaWorker(final Callable<T> task) {
        final CompletableFuture<T> future = new CompletableFuture<>();
        try {
            Via.getPlatform().runAsync(() -> {
                try {
                    future.complete(task.call());
                } catch (Throwable e) {
                    future.completeExceptionally(e);
                }
            });
        } catch (Throwable e) {
            future.completeExceptionally(e);
        }
        return future;
    }

    public boolean hasJavaClientAccepted() {
        return this.javaClientAccepted;
    }

    public void setJavaClientAccepted() {
        this.javaClientAccepted = true;
    }

    public UUID httpToken() {
        return this.httpToken;
    }

    public void setHttpToken(final UUID httpToken) {
        if (this.httpToken != null) {
            throw new IllegalStateException("Resource pack HTTP token was already assigned");
        }
        this.httpToken = Objects.requireNonNull(httpToken, "httpToken");
    }

    public RemotePackServiceClient.Lookup remotePackLookup() {
        return this.remotePackLookup;
    }

    public CompletableFuture<RemotePackServiceClient.Lookup> remotePackLookupFuture() {
        return this.remotePackLookupFuture;
    }

    public void setRemotePackLookupFuture(
            final CompletableFuture<RemotePackServiceClient.Lookup> remotePackLookupFuture) {
        if (this.remotePackLookupFuture != null) {
            throw new IllegalStateException("Remote resource pack lookup future was already assigned");
        }
        this.remotePackLookupFuture = Objects.requireNonNull(remotePackLookupFuture, "remotePackLookupFuture")
                .thenApply(lookup -> {
                    this.remotePackLookup = Objects.requireNonNull(lookup, "remotePackLookup");
                    return lookup;
                });
    }

    public record Info(ResourcePack.Key key, long announcedSize, byte[] contentKey, String contentId,
                       String announcedSubpacks, URL httpUrl, String announcedCdnUrl,
                       boolean hasScripts, boolean addonPack, boolean rayTracingCapable) {

        public Info {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(contentKey, "contentKey");
            Objects.requireNonNull(contentId, "contentId");
            Objects.requireNonNull(announcedSubpacks, "announcedSubpacks");
            Objects.requireNonNull(announcedCdnUrl, "announcedCdnUrl");
            if (announcedSize < -1L) {
                throw new IllegalArgumentException("Resource pack announced size must be non-negative or unknown");
            }
            contentKey = contentKey.clone();
        }

        public Info(final ResourcePack.Key key, final long announcedSize, final byte[] contentKey,
                    final String contentId, final String announcedSubpacks, final URL httpUrl) {
            this(key, announcedSize, contentKey, contentId, announcedSubpacks, httpUrl,
                    httpUrl != null ? httpUrl.toExternalForm() : "", false, false, false);
        }

        public Info(final ResourcePack.Key key, final byte[] contentKey, final String contentId, final URL httpUrl) {
            this(key, -1L, contentKey, contentId, "", httpUrl);
        }

        @Override
        public byte[] contentKey() {
            return this.contentKey.clone();
        }

        PackAlias alias(final String backendScope) {
            return PackAlias.from(backendScope, this.key, this.announcedSize, this.contentId, this.contentKey);
        }

        boolean hasSameAnnouncement(final Info other) {
            return this.key.equals(other.key)
                    && this.announcedSize == other.announcedSize
                    && Arrays.equals(this.contentKey, other.contentKey)
                    && this.contentId.equals(other.contentId)
                    && this.announcedSubpacks.equals(other.announcedSubpacks)
                    && Objects.equals(this.httpUrl, other.httpUrl)
                    && this.announcedCdnUrl.equals(other.announcedCdnUrl)
                    && this.hasScripts == other.hasScripts
                    && this.addonPack == other.addonPack
                    && this.rayTracingCapable == other.rayTracingCapable;
        }
    }

    public record ResolvedRequest(ResourcePack.Key key, Info info) {

        public ResolvedRequest {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(info, "info");
            if (!key.equals(info.key())) {
                throw new IllegalArgumentException("Resolved resource pack key does not match its announcement");
            }
        }
    }

    public record AnnouncementHeader(boolean resourcePackRequired, boolean hasAddonPacks, boolean hasScripts,
                                     boolean forceDisableVibrantVisuals, UUID worldTemplateId,
                                     String worldTemplateVersion) {

        public AnnouncementHeader {
            Objects.requireNonNull(worldTemplateId, "worldTemplateId");
            Objects.requireNonNull(worldTemplateVersion, "worldTemplateVersion");
        }

        private static AnnouncementHeader compatibility() {
            return new AnnouncementHeader(false, false, false, false, new UUID(0L, 0L), "");
        }
    }

    public record PreparedStack(List<ResourcePack> resourcePacksTopToBottom,
                                List<String> selectedSubpacksTopToBottom) {

        public PreparedStack {
            resourcePacksTopToBottom = List.copyOf(resourcePacksTopToBottom);
            selectedSubpacksTopToBottom = List.copyOf(selectedSubpacksTopToBottom);
            if (resourcePacksTopToBottom.size() != selectedSubpacksTopToBottom.size()) {
                throw new IllegalArgumentException("Resource pack and subpack stack sizes differ");
            }
        }
    }

    @FunctionalInterface
    interface StackPreparationExecutor {
        CompletableFuture<PreparedStack> submit(Callable<PreparedStack> task);
    }

    static String fingerprintAnnouncementSequence(final AnnouncementHeader header, final Info[] infos) {
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        updateBytes(digest, "ViaBedrock-ResourcePacksInfo-v1\0".getBytes(StandardCharsets.US_ASCII));
        updateBoolean(digest, header.resourcePackRequired());
        updateBoolean(digest, header.hasAddonPacks());
        updateBoolean(digest, header.hasScripts());
        updateBoolean(digest, header.forceDisableVibrantVisuals());
        updateLong(digest, header.worldTemplateId().getMostSignificantBits());
        updateLong(digest, header.worldTemplateId().getLeastSignificantBits());
        updateString(digest, header.worldTemplateVersion());
        updateInt(digest, infos.length);
        for (Info info : infos) {
            updateLong(digest, info.key().id().getMostSignificantBits());
            updateLong(digest, info.key().id().getLeastSignificantBits());
            updateString(digest, info.key().version());
            updateLong(digest, info.announcedSize());
            updateBytes(digest, info.contentKey);
            updateString(digest, info.announcedSubpacks());
            updateString(digest, info.contentId());
            updateBoolean(digest, info.hasScripts());
            updateBoolean(digest, info.addonPack());
            updateBoolean(digest, info.rayTracingCapable());
            updateString(digest, info.announcedCdnUrl());
        }
        return toHex(digest.digest());
    }

    private static String backendScope(final UserConnection user) {
        final SocketAddress remoteAddress = user.getChannel().remoteAddress();
        if (remoteAddress instanceof InetSocketAddress inetAddress) {
            final String host = inetAddress.getAddress() != null
                    ? inetAddress.getAddress().getHostAddress()
                    : inetAddress.getHostString().toLowerCase(Locale.ROOT);
            return "inet:" + host + ':' + inetAddress.getPort();
        }
        return remoteAddress != null ? remoteAddress.getClass().getName() + ':' + remoteAddress : "";
    }

    private static void updateBoolean(final MessageDigest digest, final boolean value) {
        digest.update(value ? (byte) 1 : (byte) 0);
    }

    private static void updateString(final MessageDigest digest, final String value) {
        updateBytes(digest, value.getBytes(StandardCharsets.UTF_8));
    }

    private static void updateBytes(final MessageDigest digest, final byte[] value) {
        updateInt(digest, value.length);
        digest.update(value);
    }

    private static void updateInt(final MessageDigest digest, final int value) {
        digest.update((byte) (value >>> 24));
        digest.update((byte) (value >>> 16));
        digest.update((byte) (value >>> 8));
        digest.update((byte) value);
    }

    private static void updateLong(final MessageDigest digest, final long value) {
        digest.update((byte) (value >>> 56));
        digest.update((byte) (value >>> 48));
        digest.update((byte) (value >>> 40));
        digest.update((byte) (value >>> 32));
        digest.update((byte) (value >>> 24));
        digest.update((byte) (value >>> 16));
        digest.update((byte) (value >>> 8));
        digest.update((byte) value);
    }

    private static String toHex(final byte[] value) {
        final StringBuilder hex = new StringBuilder(value.length * 2);
        for (byte element : value) {
            hex.append(Character.forDigit(Byte.toUnsignedInt(element) >>> 4, 16));
            hex.append(Character.forDigit(Byte.toUnsignedInt(element) & 0x0F, 16));
        }
        return hex.toString();
    }

}
