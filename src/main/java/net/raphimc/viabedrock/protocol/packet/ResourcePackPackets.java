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
package net.raphimc.viabedrock.protocol.packet;

import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.protocol.packet.PacketWrapper;
import com.viaversion.viaversion.api.protocol.packet.State;
import com.viaversion.viaversion.api.protocol.remapper.PacketHandler;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import com.viaversion.viaversion.api.type.Type;
import com.viaversion.viaversion.api.type.Types;
import com.viaversion.viaversion.protocols.v1_21_7to1_21_9.packet.ClientboundConfigurationPackets1_21_9;
import com.viaversion.viaversion.protocols.v1_21_7to1_21_9.packet.ServerboundConfigurationPackets1_21_9;
import io.netty.buffer.ByteBuf;
import net.raphimc.viabedrock.ViaBedrock;
import net.raphimc.viabedrock.api.resourcepack.ResourcePack;
import net.raphimc.viabedrock.api.resourcepack.cache.ArchiveDigest;
import net.raphimc.viabedrock.api.util.TextUtil;
import net.raphimc.viabedrock.experimental.ExperimentalFeatures;
import net.raphimc.viabedrock.experimental.custommapping.CustomMappingSyncStorage;
import net.raphimc.viabedrock.experimental.resourcepack.ResourcePackModule;
import net.raphimc.viabedrock.experimental.resourcepack.cache.ResourcePackArchiveStore;
import net.raphimc.viabedrock.protocol.BedrockProtocol;
import net.raphimc.viabedrock.protocol.ClientboundBedrockPackets;
import net.raphimc.viabedrock.protocol.ServerboundBedrockPackets;
import net.raphimc.viabedrock.protocol.data.ProtocolConstants;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.PackType;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ResourcePackResponse;
import net.raphimc.viabedrock.protocol.data.enums.java.generated.ResourcePackAction;
import net.raphimc.viabedrock.protocol.model.Experiment;
import net.raphimc.viabedrock.protocol.storage.ResourcePackDownloadTracker;
import net.raphimc.viabedrock.protocol.storage.ResourcePackLoadStateTracker;
import net.raphimc.viabedrock.protocol.storage.ResourcePackStorage;
import net.raphimc.viabedrock.protocol.types.BedrockTypes;

import java.lang.ref.WeakReference;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.logging.Level;

public class ResourcePackPackets {

    private static final int RESOURCE_PACK_CHUNK_REQUEST_WINDOW = 4;
    private static final Type<byte[]> RESOURCE_PACK_CONTENT_KEY = new BoundedByteArrayType(0, 4 * 1024);
    private static final Type<byte[]> RESOURCE_PACK_HASH = new BoundedByteArrayType(32, 32);
    private static final Type<byte[]> RESOURCE_PACK_CHUNK = new BoundedByteArrayType(
            1, ResourcePackDownloadTracker.MAX_CHUNK_BYTES);

    public static void register(final BedrockProtocol protocol) {
        protocol.registerClientboundTransition(ClientboundBedrockPackets.RESOURCE_PACKS_INFO,
                ClientboundConfigurationPackets1_21_9.RESOURCE_PACK_PUSH, (PacketHandler) wrapper -> {
                    if (wrapper.user().has(ResourcePackLoadStateTracker.class) || wrapper.user().has(ResourcePackStorage.class)) {
                        ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Received RESOURCE_PACKS_INFO after resource pack negotiation was already started/finished");
                        wrapper.cancel();
                        return;
                    }
                    final boolean resourcePackRequired = wrapper.read(Types.BOOLEAN);
                    final boolean hasAddonPacks = wrapper.read(Types.BOOLEAN);
                    final boolean hasScripts = wrapper.read(Types.BOOLEAN);
                    final boolean forceDisableVibrantVisuals = wrapper.read(Types.BOOLEAN);
                    final UUID worldTemplateId = wrapper.read(BedrockTypes.UUID);
                    final String worldTemplateVersion = wrapper.read(BedrockTypes.STRING);
                    final ResourcePackLoadStateTracker.Info[] infos = new ResourcePackLoadStateTracker.Info[wrapper.read(BedrockTypes.UNSIGNED_SHORT_LE)]; // resource packs size
                    for (int i = 0; i < infos.length; i++) {
                        final UUID id = wrapper.read(BedrockTypes.UUID); // pack id
                        final String version = wrapper.read(BedrockTypes.STRING); // pack version
                        final long packSize = wrapper.read(BedrockTypes.UNSIGNED_LONG_LE); // pack size
                        final byte[] contentKey = wrapper.read(RESOURCE_PACK_CONTENT_KEY); // content key
                        final String subpackNames = wrapper.read(BedrockTypes.STRING); // subpack names
                        final String contentId = wrapper.read(BedrockTypes.STRING); // content identity
                        final boolean packHasScripts = wrapper.read(Types.BOOLEAN);
                        final boolean addonPack = wrapper.read(Types.BOOLEAN);
                        final boolean rayTracingCapable = wrapper.read(Types.BOOLEAN);
                        final String cdnUrlString = wrapper.read(BedrockTypes.STRING);
                        URL cdnUrl = null;
                        try {
                            if (!cdnUrlString.isEmpty()) {
                                cdnUrl = new URL(cdnUrlString);
                            }
                        } catch (MalformedURLException ignored) {
                        }
                        infos[i] = new ResourcePackLoadStateTracker.Info(
                                new ResourcePack.Key(id, version), packSize, contentKey, contentId, subpackNames,
                                cdnUrl, cdnUrlString, packHasScripts, addonPack, rayTracingCapable);
                    }
                    final ResourcePackLoadStateTracker.AnnouncementHeader announcementHeader =
                            new ResourcePackLoadStateTracker.AnnouncementHeader(
                                    resourcePackRequired, hasAddonPacks, hasScripts, forceDisableVibrantVisuals,
                                    worldTemplateId, worldTemplateVersion);
                    final ResourcePackLoadStateTracker loadStateTracker =
                            new ResourcePackLoadStateTracker(wrapper.user(), infos, announcementHeader);
                    wrapper.user().put(loadStateTracker);

                    if (ViaBedrock.getConfig().shouldTranslateResourcePacks() && wrapper.user().getProtocolInfo().protocolVersion().newerThanOrEqualTo(ProtocolVersion.v1_21_7)) {
                        final UUID httpToken = UUID.randomUUID();
                        loadStateTracker.setHttpToken(httpToken);
                        ViaBedrock.getResourcePackServer().addConnection(
                                httpToken, wrapper.user().getChannel().closeFuture());
                        final String resourcePackUrl = ViaBedrock.getResourcePackServer().getUrl() + "?token=" + httpToken;

                        wrapper.write(Types.UUID, httpToken); // id
                        wrapper.write(Types.STRING, resourcePackUrl); // url
                        wrapper.write(Types.STRING, ""); // hash is unknown until the exact stack has been verified
                        wrapper.write(Types.BOOLEAN, false); // required
                        wrapper.write(Types.OPTIONAL_TAG, TextUtil.stringToNbt(
                                "\n§aIf you press 'Yes', the resource packs will be downloaded and converted to the Java Edition format. " +
                                        "This may take a while, depending on your internet connection and the size of the packs. " +
                                        "If you press 'No', you can join without loading the resource packs but you will have a worse gameplay experience.")
                        ); // prompt
                    } else {
                        wrapper.cancel();
                        final PacketWrapper resourcePack = PacketWrapper.create(ServerboundConfigurationPackets1_21_9.RESOURCE_PACK, wrapper.user());
                        resourcePack.write(Types.UUID, UUID.randomUUID()); // id
                        resourcePack.write(Types.VAR_INT, ResourcePackAction.DECLINED.ordinal()); // action
                        resourcePack.sendToServer(BedrockProtocol.class, false);
                    }
                }, State.PLAY, (PacketHandler) PacketWrapper::cancel // Bedrock client ignores resource packs after the initial info packet
        );
        protocol.registerClientbound(ClientboundBedrockPackets.RESOURCE_PACK_STACK, null, wrapper -> {
            wrapper.cancel();
            final UserConnection user = wrapper.user();
            final ResourcePackLoadStateTracker loadStateTracker = wrapper.user().remove(ResourcePackLoadStateTracker.class);
            if (loadStateTracker != null) {
                wrapper.read(Types.BOOLEAN); // resource pack required
                final ResourcePack.Key[] keys = new ResourcePack.Key[wrapper.read(BedrockTypes.UNSIGNED_VAR_INT)]; // resource packs size
                final String[] subpacks = new String[keys.length];
                for (int i = 0; i < keys.length; i++) {
                    final UUID id = UUID.fromString(wrapper.read(BedrockTypes.STRING)); // id
                    final String version = wrapper.read(BedrockTypes.STRING); // version
                    subpacks[i] = wrapper.read(BedrockTypes.STRING); // subpack name
                    keys[i] = new ResourcePack.Key(id, version);
                }
                wrapper.read(BedrockTypes.STRING); // base game version
                final Experiment[] experiments = wrapper.read(BedrockTypes.EXPERIMENT_ARRAY); // experiments
                wrapper.read(Types.BOOLEAN); // experiments previously toggled
                wrapper.read(Types.BOOLEAN); // include editor packs
                for (Experiment experiment : experiments) {
                    if (experiment.enabled()) {
                        ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "This server uses an experimental resource pack: " + experiment.name());
                    }
                }

                final boolean delayAfterBuild = !loadStateTracker.hasJavaClientAccepted();
                final AtomicReference<ResourcePackStorage> acquiredStorage = new AtomicReference<>();
                final AtomicReference<Throwable> buildAbortReason = new AtomicReference<>();
                final CompletableFuture<Void> connectionClosed = new CompletableFuture<>();
                user.getChannel().closeFuture().addListener(ignored -> {
                    final CancellationException cancellation = new CancellationException(
                            "Resource pack connection closed during runtime build");
                    buildAbortReason.compareAndSet(null, cancellation);
                    cleanupStorage(acquiredStorage.getAndSet(null));
                    connectionClosed.completeExceptionally(cancellation);
                });
                final CompletableFuture<ResourcePackStorage> build = loadStateTracker
                        .prepareResourcePackStackAsync(keys, subpacks)
                        .thenCompose(preparedStack -> ResourcePackStorage.createAsync(
                                preparedStack.resourcePacksTopToBottom(),
                                preparedStack.selectedSubpacksTopToBottom()))
                        .thenCompose(resourcePackStorage -> {
                            acquiredStorage.set(resourcePackStorage);
                            final Throwable abortReason = buildAbortReason.get();
                            if (abortReason != null) {
                                cleanupStorage(acquiredStorage.getAndSet(null));
                                return CompletableFuture.failedFuture(abortReason);
                            }
                            return ResourcePackModule.ensureRuntimeData(resourcePackStorage)
                                    .thenApply(ignored -> resourcePackStorage);
                        }).whenComplete((resourcePackStorage, error) -> {
                            if (error != null) cleanupStorage(acquiredStorage.getAndSet(null));
                        });
                final CompletableFuture<ResourcePackStorage> connectedBuild = detachedCancellation(
                        build, connectionClosed, ResourcePackPackets::cleanupStorage);
                detachedTimeout(connectedBuild,
                        ViaBedrock.getConfig().getResourcePackCacheBuildTimeoutSeconds(), TimeUnit.SECONDS,
                        () -> {
                            buildAbortReason.compareAndSet(null,
                                    new TimeoutException("Resource pack runtime build timed out"));
                            cleanupStorage(acquiredStorage.getAndSet(null));
                        }, ResourcePackPackets::cleanupStorage, user.getChannel().eventLoop())
                        .whenComplete((resourcePackStorage, error) -> {
                            if (error == null) acquiredStorage.compareAndSet(resourcePackStorage, null);
                            finishResourcePackStackBuild(
                                    user, loadStateTracker, resourcePackStorage, error, delayAfterBuild);
                        });
                return;
            }

            delayResourcePackStackFinished(user);
        });
        protocol.registerClientbound(ClientboundBedrockPackets.RESOURCE_PACK_DATA_INFO, null, wrapper -> {
            wrapper.cancel();
            final String key = wrapper.read(BedrockTypes.STRING); // resource name
            final long chunkSize = wrapper.read(BedrockTypes.UNSIGNED_INT_LE); // chunk size
            wrapper.read(BedrockTypes.UNSIGNED_INT_LE); // announced number of chunks
            final long size = wrapper.read(BedrockTypes.UNSIGNED_LONG_LE); // file size
            final byte[] hash = wrapper.read(RESOURCE_PACK_HASH); // file hash
            final boolean premium = wrapper.read(Types.BOOLEAN); // is premium pack
            final PackType type = PackType.getByValue(wrapper.read(Types.UNSIGNED_BYTE), PackType.Invalid); // pack type

            final ResourcePack.Key packKey = ResourcePack.Key.fromString(key);
            final ResourcePackLoadStateTracker loadStateTracker = wrapper.user().get(ResourcePackLoadStateTracker.class);
            final ResourcePackLoadStateTracker.Info info = loadStateTracker != null ? loadStateTracker.getRequest(packKey) : null;
            try {
                ResourcePackDownloadTracker.validateMetadata(size, chunkSize, hash);
                if (info != null && info.announcedSize() >= 0L && info.announcedSize() != size) {
                    throw new IllegalStateException("Resource pack size changed during negotiation: "
                            + size + " != " + info.announcedSize());
                }
            } catch (Throwable e) {
                BedrockProtocol.kickForIllegalState(wrapper.user(), "Invalid server resource pack metadata", e);
                return;
            }
            final boolean sharedCacheEnabled = ViaBedrock.isSharedResourcePackCacheEnabled();
            final ResourcePackArchiveStore.Claim archiveClaim = shouldClaimRawArchive(
                    sharedCacheEnabled, type, info)
                    ? ViaBedrock.getResourcePackArchiveStore().claim(hash) : null;
            if (archiveClaim != null) {
                if (!archiveClaim.leader()) {
                    loadClaimedPackOrTakeOver(wrapper.user(), loadStateTracker, key, packKey, info,
                            size, chunkSize, hash, premium, type, archiveClaim);
                } else {
                    tryLegacyPackBeforeChunks(wrapper.user(), loadStateTracker, key, packKey, info,
                            size, chunkSize, hash, premium, type, archiveClaim);
                }
                return;
            }
            startResourcePackDownload(wrapper.user(), key, size, chunkSize, hash, premium, type, null);
        });
        protocol.registerClientbound(ClientboundBedrockPackets.RESOURCE_PACK_CHUNK_DATA, null, wrapper -> {
            wrapper.cancel();
            final String key = wrapper.read(BedrockTypes.STRING); // resource name
            final long chunk = wrapper.read(BedrockTypes.UNSIGNED_INT_LE); // chunk id
            final long byteOffset = wrapper.read(BedrockTypes.UNSIGNED_LONG_LE); // byte offset
            final byte[] data = wrapper.read(RESOURCE_PACK_CHUNK); // chunk data

            final UserConnection user = wrapper.user();
            final ResourcePackDownloadTracker downloadTracker = user.get(ResourcePackDownloadTracker.class);
            final ResourcePackDownloadTracker.Download download = downloadTracker.get(key);
            if (download != null) {
                download.processDataChunkAsync(
                                ViaBedrock.getResourcePackWorkScheduler(), chunk, byteOffset, data)
                        .whenComplete((completedFile, error) -> user.getChannel().eventLoop().execute(
                                () -> handleResourcePackChunkWrite(
                                        user, key, download, completedFile, error)));
            } else {
                ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Received RESOURCE_PACK_CHUNK_DATA for unknown pack: " + key);
            }
        });

        protocol.registerServerboundTransition(ServerboundConfigurationPackets1_21_9.RESOURCE_PACK, ServerboundBedrockPackets.RESOURCE_PACK_CLIENT_RESPONSE, wrapper -> {
            wrapper.read(Types.UUID); // id
            final ResourcePackAction action = ResourcePackAction.values()[wrapper.read(Types.VAR_INT)]; // action
            switch (action) {
                case SUCCESSFULLY_LOADED -> {
                    final ResourcePackStorage resourcePackStorage = wrapper.user().get(ResourcePackStorage.class);
                    if (resourcePackStorage != null) {
                        resourcePackStorage.setLoadedOnJavaClient();
                    }
                    wrapper.cancel();
                    delayResourcePackStackFinished(wrapper.user());
                }
                case FAILED_DOWNLOAD, FAILED_RELOAD, DISCARDED -> {
                    ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Client resource pack download/load failed");
                    wrapper.cancel();
                    delayResourcePackStackFinished(wrapper.user());
                }
                case DECLINED, INVALID_URL -> {
                    wrapper.write(Types.BYTE, (byte) ResourcePackResponse.DownloadingFinished.getValue()); // status
                    wrapper.write(BedrockTypes.SHORT_LE_STRING_ARRAY, new String[0]); // downloading packs
                }
                case ACCEPTED -> {
                    final ResourcePackLoadStateTracker loadStateTracker = wrapper.user().get(ResourcePackLoadStateTracker.class);
                    if (loadStateTracker != null) {
                        wrapper.cancel();
                        loadStateTracker.setJavaClientAccepted();
                        final WeakReference<UserConnection> userReference = new WeakReference<>(wrapper.user());
                        loadStateTracker.loadRequestedResourcePacks().whenComplete((ignored, error) -> {
                            final UserConnection liveUser = userReference.get();
                            if (!isConnectionActive(liveUser)) {
                                return;
                            }
                            if (error != null) {
                                BedrockProtocol.kickForIllegalState(liveUser,
                                        "One of the server resource packs failed to load. Try again later or decline the resource packs.",
                                        error);
                                return;
                            }
                            try {
                                final PacketWrapper resourcePackClientResponse = PacketWrapper.create(
                                        ServerboundBedrockPackets.RESOURCE_PACK_CLIENT_RESPONSE, liveUser);
                                resourcePackClientResponse.write(Types.BYTE,
                                        (byte) ResourcePackResponse.DownloadingFinished.getValue()); // status
                                resourcePackClientResponse.write(
                                        BedrockTypes.SHORT_LE_STRING_ARRAY, new String[0]); // downloading packs
                                resourcePackClientResponse.scheduleSendToServer(BedrockProtocol.class);
                            } catch (Throwable sendError) {
                                BedrockProtocol.kickForIllegalState(liveUser,
                                        "Failed to finish server resource pack downloads", sendError);
                            }
                        });
                    } else {
                        wrapper.write(Types.BYTE, (byte) ResourcePackResponse.DownloadingFinished.getValue()); // status
                        wrapper.write(BedrockTypes.SHORT_LE_STRING_ARRAY, new String[0]); // downloading packs
                    }
                }
                case DOWNLOADED -> wrapper.cancel();
                default -> throw new IllegalStateException("Unhandled ResourcePackAction: " + action);
            }
        });
    }

    private static void finishResourcePackStackBuild(final UserConnection user,
                                                     final ResourcePackLoadStateTracker loadStateTracker,
                                                     final ResourcePackStorage resourcePackStorage,
                                                     final Throwable buildError,
                                                     final boolean delayAfterBuild) {
        final AtomicBoolean storagePublished = new AtomicBoolean();
        if (resourcePackStorage != null) {
            user.getChannel().closeFuture().addListener(ignored -> {
                if (!storagePublished.get()) {
                    resourcePackStorage.onRemove();
                }
            });
        }

        final Runnable publish = () -> {
            if (buildError != null) {
                if (resourcePackStorage != null) {
                    resourcePackStorage.onRemove();
                }
                failHttpConnection(loadStateTracker, buildError);
                if (user.getChannel().isActive()) {
                    BedrockProtocol.kickForIllegalState(
                            user, "Failed to build the shared resource pack runtime", buildError);
                }
                return;
            }
            if (!user.getChannel().isActive()) {
                resourcePackStorage.onRemove();
                failHttpConnection(loadStateTracker,
                        new CancellationException("Resource pack connection closed before runtime publication"));
                return;
            }

            try {
                resourcePackStorage.setSupportsFreeRotation(user.getProtocolInfo().protocolVersion()
                        .newerThanOrEqualTo(ProtocolVersion.v1_21_11));
                user.put(resourcePackStorage);
                storagePublished.set(true);
                if (ViaBedrock.getResourcePackServer() != null && loadStateTracker.httpToken() != null) {
                    ViaBedrock.getResourcePackServer().completeConnection(
                            loadStateTracker.httpToken(), resourcePackStorage);
                }
                ExperimentalFeatures.dispatchResourcePackStackSet(user);
                if (delayAfterBuild) {
                    delayResourcePackStackFinished(user);
                }
            } catch (Throwable publishError) {
                if (user.get(ResourcePackStorage.class) == resourcePackStorage) {
                    user.remove(ResourcePackStorage.class);
                } else {
                    resourcePackStorage.onRemove();
                }
                failHttpConnection(loadStateTracker, publishError);
                BedrockProtocol.kickForIllegalState(
                        user, "Failed to publish the shared resource pack runtime", publishError);
            }
        };
        final RejectedExecutionException rejection = executeOnEventLoop(
                user.getChannel().eventLoop(), publish,
                () -> {
                    if (resourcePackStorage != null) {
                        resourcePackStorage.onRemove();
                    }
                });
        if (rejection != null) {
            failHttpConnection(loadStateTracker, rejection);
            BedrockProtocol.kickForIllegalState(
                    user, "Failed to publish the shared resource pack runtime", rejection);
        }
    }

    static RejectedExecutionException executeOnEventLoop(final Executor eventLoop, final Runnable task,
                                                         final Runnable rejectedCleanup) {
        try {
            eventLoop.execute(task);
            return null;
        } catch (RejectedExecutionException e) {
            try {
                rejectedCleanup.run();
            } catch (Throwable cleanupError) {
                e.addSuppressed(cleanupError);
            }
            return e;
        }
    }

    static <T> CompletableFuture<T> detachedTimeout(final CompletionStage<T> source, final long timeout,
                                                    final TimeUnit unit, final Runnable timeoutCleanup,
                                                    final Consumer<T> lateValueCleanup,
                                                    final ScheduledExecutorService timeoutScheduler) {
        final CompletableFuture<T> bounded = new CompletableFuture<>();
        final AtomicReference<ScheduledFuture<?>> timeoutTask = new AtomicReference<>();
        source.whenComplete((value, error) -> {
            if (error != null) {
                bounded.completeExceptionally(error);
            } else if (!bounded.complete(value)) {
                lateValueCleanup.accept(value);
            }
            final ScheduledFuture<?> scheduled = timeoutTask.get();
            if (scheduled != null) scheduled.cancel(false);
        });
        try {
            final ScheduledFuture<?> scheduled = timeoutScheduler.schedule(() -> {
                if (bounded.completeExceptionally(new TimeoutException(
                        "Resource pack build exceeded " + timeout + ' ' + unit.name().toLowerCase()))) {
                    timeoutCleanup.run();
                }
            }, timeout, unit);
            timeoutTask.set(scheduled);
            if (bounded.isDone()) scheduled.cancel(false);
        } catch (RejectedExecutionException e) {
            if (bounded.completeExceptionally(e)) {
                timeoutCleanup.run();
            }
        }
        return bounded;
    }

    static boolean shouldClaimRawArchive(final boolean sharedCacheEnabled, final PackType type,
                                         final ResourcePackLoadStateTracker.Info info) {
        return sharedCacheEnabled && type == PackType.Resources && info != null;
    }

    static <T> CompletableFuture<T> detachedCancellation(final CompletionStage<T> source,
                                                         final CompletionStage<?> cancellation,
                                                         final Consumer<T> lateValueCleanup) {
        final CompletableFuture<T> dependent = new CompletableFuture<>();
        cancellation.whenComplete((ignored, error) -> dependent.completeExceptionally(
                error != null ? error : new CancellationException("Resource pack build waiter cancelled")));
        source.whenComplete((value, error) -> {
            if (error != null) {
                dependent.completeExceptionally(error);
            } else if (!dependent.complete(value)) {
                lateValueCleanup.accept(value);
            }
        });
        return dependent;
    }

    private static void cleanupStorage(final ResourcePackStorage storage) {
        if (storage != null) storage.onRemove();
    }

    private static void failHttpConnection(final ResourcePackLoadStateTracker loadStateTracker,
                                           final Throwable error) {
        if (ViaBedrock.getResourcePackServer() != null && loadStateTracker.httpToken() != null) {
            ViaBedrock.getResourcePackServer().failConnection(loadStateTracker.httpToken(), error);
        }
    }

    private static void delayResourcePackStackFinished(final UserConnection user) {
        user.get(CustomMappingSyncStorage.class).delayResourcePackStackFinishedIfNeeded(() -> sendResourcePackStackFinished(user));
    }

    private static void requestResourcePackChunks(final UserConnection user, final String key,
                                                  final ResourcePackDownloadTracker.Download download,
                                                  final int maximumRequests) {
        for (int i = 0; i < maximumRequests; i++) {
            final long chunk = download.claimNextChunkRequest();
            if (chunk < 0L) {
                return;
            }
            final PacketWrapper request = PacketWrapper.create(
                    ServerboundBedrockPackets.RESOURCE_PACK_CHUNK_REQUEST, user);
            request.write(BedrockTypes.STRING, key); // resource name
            request.write(BedrockTypes.UNSIGNED_INT_LE, chunk); // chunk
            try {
                request.sendToServer(BedrockProtocol.class);
            } catch (Exception e) {
                throw new IllegalStateException("Failed to send resource pack chunk request", e);
            }
        }
    }

    private static void tryLegacyPackBeforeChunks(final UserConnection user,
                                                  final ResourcePackLoadStateTracker loadStateTracker,
                                                  final String key, final ResourcePack.Key packKey,
                                                  final ResourcePackLoadStateTracker.Info info,
                                                  final long size, final long chunkSize, final byte[] hash,
                                                  final boolean premium, final PackType type,
                                                  final ResourcePackArchiveStore.Claim claim) {
        final ResourcePackArchiveStore store = ViaBedrock.getResourcePackArchiveStore();
        final CompletableFuture<Boolean> importFuture = store.tryImportLegacy(claim, packKey);
        final CompletableFuture<Void> connectionStage = new CompletableFuture<>();
        connectionStage.whenComplete((ignored, error) -> {
            if (connectionStage.isCancelled()) {
                abandonClaimIfOpen(store, claim,
                        new CancellationException("Resource pack connection closed during legacy CAS import"));
            }
        });
        final WeakReference<UserConnection> userReference = new WeakReference<>(user);
        final WeakReference<ResourcePackLoadStateTracker> loadTrackerReference =
                new WeakReference<>(loadStateTracker);
        importFuture.whenComplete((imported, importError) -> {
            if (connectionStage.isCancelled()) {
                return;
            }
            final UserConnection liveUser = userReference.get();
            final ResourcePackLoadStateTracker liveLoadTracker = loadTrackerReference.get();
            if (!isConnectionActive(liveUser) || liveLoadTracker == null) {
                abandonClaimIfOpen(store, claim,
                        new CancellationException("Resource pack connection closed before legacy CAS import completed"));
                connectionStage.complete(null);
                return;
            }
            final Runnable continuation = () -> {
                if (!isConnectionActive(liveUser)
                        || liveUser.get(ResourcePackLoadStateTracker.class) != liveLoadTracker) {
                    abandonClaimIfOpen(store, claim,
                            new CancellationException("Resource pack connection closed before legacy CAS import completed"));
                    connectionStage.complete(null);
                    return;
                }
                if (Boolean.TRUE.equals(imported)) {
                    loadClaimedPack(liveUser, liveLoadTracker, packKey, info, claim);
                } else if (claim.path().isCompletedExceptionally()) {
                    final Throwable failure = importError != null ? importError
                            : new IllegalStateException("Legacy resource pack CAS import failed");
                    BedrockProtocol.kickForIllegalState(
                            liveUser, "Failed to import a legacy server resource pack", failure);
                } else {
                    startResourcePackDownload(
                            liveUser, key, size, chunkSize, hash, premium, type, claim);
                }
                connectionStage.complete(null);
            };
            final RejectedExecutionException rejection = executeOnEventLoop(
                    liveUser.getChannel().eventLoop(), continuation,
                    () -> abandonClaimIfOpen(store, claim,
                            new CancellationException("Resource pack event loop stopped during legacy CAS import")));
            if (rejection != null) {
                connectionStage.completeExceptionally(rejection);
            }
        });
        final ResourcePackDownloadTracker downloadTracker = user.get(ResourcePackDownloadTracker.class);
        if (downloadTracker != null) {
            downloadTracker.trackConnectionStage(connectionStage);
        } else {
            connectionStage.cancel(false);
        }
    }

    private static void startResourcePackDownload(final UserConnection user, final String key,
                                                  final long size, final long chunkSize, final byte[] hash,
                                                  final boolean premium, final PackType type,
                                                  final ResourcePackArchiveStore.Claim archiveClaim) {
        final ResourcePackDownloadTracker downloadTracker = user.get(ResourcePackDownloadTracker.class);
        if (downloadTracker == null || !isConnectionActive(user)) {
            if (archiveClaim != null) {
                abandonClaimIfOpen(ViaBedrock.getResourcePackArchiveStore(), archiveClaim,
                        new CancellationException("Resource pack connection closed before download started"));
            }
            return;
        }
        final ResourcePackDownloadTracker.Download download;
        try {
            download = downloadTracker.add(key, size, chunkSize, hash, premium, type, archiveClaim);
        } catch (Throwable e) {
            if (archiveClaim != null && archiveClaim.leader()) {
                if (e instanceof CancellationException || !isConnectionActive(user)) {
                    ViaBedrock.getResourcePackArchiveStore().abandon(archiveClaim, e);
                } else {
                    ViaBedrock.getResourcePackArchiveStore().fail(archiveClaim, e);
                }
            }
            if (isConnectionActive(user)) {
                BedrockProtocol.kickForIllegalState(user, "Failed to start a server resource pack download", e);
            }
            return;
        }
        try {
            requestResourcePackChunks(user, key, download, RESOURCE_PACK_CHUNK_REQUEST_WINDOW);
        } catch (Throwable e) {
            downloadTracker.cancel(key, e);
            if (isConnectionActive(user)) {
                BedrockProtocol.kickForIllegalState(user, "Failed to request a server resource pack", e);
            }
        }
    }

    private static void abandonClaimIfOpen(final ResourcePackArchiveStore store,
                                           final ResourcePackArchiveStore.Claim claim,
                                           final Throwable reason) {
        if (!claim.path().isDone()) {
            store.abandon(claim, reason);
        } else {
            claim.close();
        }
    }

    private static void loadClaimedPack(final UserConnection user, final ResourcePackLoadStateTracker loadStateTracker,
                                        final ResourcePack.Key packKey, final ResourcePackLoadStateTracker.Info info,
                                        final ResourcePackArchiveStore.Claim claim) {
        attachClaimedPack(user, loadStateTracker, packKey, info, claim, claim.path(),
                "Failed to load a server resource pack");
    }

    private static void loadClaimedPackOrTakeOver(
            final UserConnection user, final ResourcePackLoadStateTracker loadStateTracker,
            final String key, final ResourcePack.Key packKey, final ResourcePackLoadStateTracker.Info info,
            final long size, final long chunkSize, final byte[] hash, final boolean premium,
            final PackType type, final ResourcePackArchiveStore.Claim claim) {
        loadClaimedPack(user, loadStateTracker, packKey, info, claim);
        final ResourcePackDownloadTracker downloadTracker = user.get(ResourcePackDownloadTracker.class);
        if (downloadTracker == null) {
            abandonClaimIfOpen(ViaBedrock.getResourcePackArchiveStore(), claim,
                    new CancellationException("Resource pack connection has no download tracker"));
            return;
        }

        final WeakReference<UserConnection> userReference = new WeakReference<>(user);
        final WeakReference<ResourcePackLoadStateTracker> loadTrackerReference =
                new WeakReference<>(loadStateTracker);
        final WeakReference<ResourcePackDownloadTracker> downloadTrackerReference =
                new WeakReference<>(downloadTracker);
        downloadTracker.trackArchiveClaim(claim).whenComplete((promoted, error) -> {
            if (error != null || !Boolean.TRUE.equals(promoted)) {
                return;
            }
            final UserConnection liveUser = userReference.get();
            final ResourcePackLoadStateTracker liveLoadTracker = loadTrackerReference.get();
            final ResourcePackDownloadTracker liveDownloadTracker = downloadTrackerReference.get();
            final ResourcePackArchiveStore store = ViaBedrock.getResourcePackArchiveStore();
            if (!isConnectionActive(liveUser) || liveLoadTracker == null || liveDownloadTracker == null) {
                abandonClaimIfOpen(store, claim,
                        new CancellationException("Promoted resource pack claimant disconnected"));
                return;
            }

            final Runnable takeOver = () -> {
                if (!isConnectionActive(liveUser)
                        || liveUser.get(ResourcePackLoadStateTracker.class) != liveLoadTracker
                        || liveUser.get(ResourcePackDownloadTracker.class) != liveDownloadTracker
                        || !claim.leader()) {
                    abandonClaimIfOpen(store, claim,
                            new CancellationException("Promoted resource pack claimant is no longer active"));
                    return;
                }
                tryLegacyPackBeforeChunks(liveUser, liveLoadTracker, key, packKey, info,
                        size, chunkSize, hash, premium, type, claim);
            };
            executeOnEventLoop(liveUser.getChannel().eventLoop(), takeOver,
                    () -> abandonClaimIfOpen(store, claim,
                            new CancellationException("Promoted resource pack claimant event loop stopped")));
        });
    }

    private static void publishClaimedPack(final UserConnection user,
                                           final ResourcePackLoadStateTracker loadStateTracker,
                                           final ResourcePack.Key packKey,
                                           final ResourcePackLoadStateTracker.Info info,
                                           final ResourcePackArchiveStore.Claim claim, final Path archive) {
        attachClaimedPack(user, loadStateTracker, packKey, info, claim,
                ViaBedrock.getResourcePackArchiveStore().publishAsync(claim, archive),
                "Failed to store a server resource pack");
    }

    private static void attachClaimedPack(final UserConnection user,
                                          final ResourcePackLoadStateTracker loadStateTracker,
                                          final ResourcePack.Key packKey,
                                          final ResourcePackLoadStateTracker.Info info,
                                          final ResourcePackArchiveStore.Claim claim,
                                          final CompletableFuture<Path> archiveFuture,
                                          final String failureMessage) {
        final WeakReference<UserConnection> userReference = new WeakReference<>(user);
        final WeakReference<ResourcePackLoadStateTracker> loadTrackerReference =
                new WeakReference<>(loadStateTracker);
        final CompletableFuture<Void> stage = attachClaimedPackWaiter(
                userReference, loadTrackerReference, packKey, info, claim, archiveFuture, failureMessage);
        final ResourcePackDownloadTracker downloadTracker = user.get(ResourcePackDownloadTracker.class);
        if (downloadTracker != null) {
            downloadTracker.trackConnectionStage(stage);
        } else {
            stage.cancel(false);
        }
    }

    static CompletableFuture<Void> attachClaimedPackWaiter(
            final WeakReference<UserConnection> userReference,
            final WeakReference<ResourcePackLoadStateTracker> loadTrackerReference,
            final ResourcePack.Key packKey, final ResourcePackLoadStateTracker.Info info,
            final ResourcePackArchiveStore.Claim claim,
            final CompletableFuture<Path> archiveFuture, final String failureMessage) {
        final CompletableFuture<Void> stage = archiveFuture.thenCompose(path -> {
            final UserConnection liveUser = userReference.get();
            final ResourcePackLoadStateTracker liveTracker = loadTrackerReference.get();
            if (!isConnectionActive(liveUser) || liveTracker == null) {
                claim.close();
                return CompletableFuture.completedFuture(null);
            }
            return ViaBedrock.getResourcePackArchiveStore().loadEffective(
                            claim, liveTracker.getAlias(packKey),
                            liveTracker.announcementSequenceFingerprint(), info.contentKey())
                    .thenAccept(pack -> {
                        final UserConnection activeUser = userReference.get();
                        final ResourcePackLoadStateTracker activeTracker = loadTrackerReference.get();
                        if (isConnectionActive(activeUser) && activeTracker != null) {
                            activeTracker.addLocalResourcePack(pack);
                        }
                    });
        }).exceptionally(error -> {
            final UserConnection liveUser = userReference.get();
            if (isConnectionActive(liveUser)) {
                BedrockProtocol.kickForIllegalState(liveUser, failureMessage, error);
            }
            return null;
        });
        stage.whenComplete((ignored, error) -> claim.close());
        return stage;
    }

    static CompletableFuture<Void> attachClaimedPackWaiter(
            final WeakReference<UserConnection> userReference,
            final WeakReference<ResourcePackLoadStateTracker> loadTrackerReference,
            final ResourcePack.Key packKey, final ResourcePackLoadStateTracker.Info info,
            final ArchiveDigest archiveDigest,
            final CompletableFuture<Path> archiveFuture, final String failureMessage) {
        return archiveFuture.thenCompose(path -> {
            final UserConnection liveUser = userReference.get();
            final ResourcePackLoadStateTracker liveTracker = loadTrackerReference.get();
            if (!isConnectionActive(liveUser) || liveTracker == null) {
                return CompletableFuture.completedFuture(null);
            }
            return ViaBedrock.getResourcePackArchiveStore().loadEffective(
                            path, archiveDigest, liveTracker.getAlias(packKey),
                            liveTracker.announcementSequenceFingerprint(), info.contentKey())
                    .thenAccept(pack -> {
                        final UserConnection activeUser = userReference.get();
                        final ResourcePackLoadStateTracker activeTracker = loadTrackerReference.get();
                        if (isConnectionActive(activeUser) && activeTracker != null) {
                            activeTracker.addLocalResourcePack(pack);
                        }
                    });
        }).exceptionally(error -> {
            final UserConnection liveUser = userReference.get();
            if (isConnectionActive(liveUser)) {
                BedrockProtocol.kickForIllegalState(liveUser, failureMessage, error);
            }
            return null;
        });
    }

    private static void handleResourcePackChunkWrite(final UserConnection user, final String key,
                                                     final ResourcePackDownloadTracker.Download download,
                                                     final Path completedFile, final Throwable error) {
        final ResourcePackDownloadTracker downloadTracker = user.get(ResourcePackDownloadTracker.class);
        if (downloadTracker == null || downloadTracker.get(key) != download) {
            return;
        }
        if (error != null) {
            downloadTracker.fail(key, error);
            if (isConnectionActive(user)) {
                BedrockProtocol.kickForIllegalState(user, "Failed to write a server resource pack chunk", error);
            }
            return;
        }
        if (!isConnectionActive(user)) {
            downloadTracker.cancel(key, new CancellationException("Resource pack connection closed"));
            return;
        }
        if (completedFile == null) {
            try {
                requestResourcePackChunks(user, key, download, 1);
            } catch (Throwable requestError) {
                downloadTracker.cancel(key, requestError);
                if (isConnectionActive(user)) {
                    BedrockProtocol.kickForIllegalState(user,
                            "Failed to request a server resource pack chunk", requestError);
                }
            }
            return;
        }

        if (download.archiveClaim() != null) {
            final ResourcePack.Key packKey = ResourcePack.Key.fromString(key);
            final ResourcePackLoadStateTracker loadStateTracker = user.get(ResourcePackLoadStateTracker.class);
            final ResourcePackLoadStateTracker.Info info =
                    loadStateTracker != null ? loadStateTracker.getRequest(packKey) : null;
            try {
                final Path archive = downloadTracker.takeCompleted(key);
                if (loadStateTracker != null && info != null) {
                    publishClaimedPack(user, loadStateTracker, packKey, info,
                            download.archiveClaim(), archive);
                } else {
                    ViaBedrock.getResourcePackArchiveStore().publishAsync(download.archiveClaim(), archive)
                            .whenComplete((path, publishFailure) -> download.archiveClaim().close())
                            .exceptionally(publishError -> {
                                if (isConnectionActive(user)) {
                                    BedrockProtocol.kickForIllegalState(
                                            user, "Failed to store a server resource pack", publishError);
                                }
                                return null;
                            });
                }
            } catch (Throwable publishError) {
                BedrockProtocol.kickForIllegalState(user, "Failed to store a server resource pack", publishError);
            }
            return;
        }

        final WeakReference<UserConnection> userReference = new WeakReference<>(user);
        final WeakReference<ResourcePackDownloadTracker> downloadTrackerReference =
                new WeakReference<>(downloadTracker);
        if (download.type() == PackType.Resources) {
            final WeakReference<ResourcePackLoadStateTracker> loadTrackerReference =
                    new WeakReference<>(user.get(ResourcePackLoadStateTracker.class));
            download.loadCompletedLegacyPackAsync(ViaBedrock.getResourcePackWorkScheduler())
                    .whenComplete((pack, loadError) -> {
                        final UserConnection liveUser = userReference.get();
                        final ResourcePackDownloadTracker liveDownloadTracker = downloadTrackerReference.get();
                        if (liveDownloadTracker == null || liveDownloadTracker.get(key) != download) {
                            return;
                        }
                        if (loadError != null) {
                            liveDownloadTracker.fail(key, loadError);
                            kickOnEventLoop(liveUser,
                                    "Failed to load a legacy server resource pack", loadError);
                            return;
                        }
                        final ResourcePackLoadStateTracker liveLoadTracker = loadTrackerReference.get();
                        if (!isConnectionActive(liveUser) || liveLoadTracker == null
                                || liveUser.get(ResourcePackLoadStateTracker.class) != liveLoadTracker) {
                            liveDownloadTracker.cancel(
                                    key, new CancellationException("Resource pack connection closed"));
                            return;
                        }
                        liveLoadTracker.addRemoteResourcePack(pack);
                        liveDownloadTracker.remove(key);
                    });
            return;
        }

        download.verifyCompletedHashAsync(ViaBedrock.getResourcePackWorkScheduler())
                .whenComplete((ignored, verifyError) -> {
                    final UserConnection liveUser = userReference.get();
                    final ResourcePackDownloadTracker liveDownloadTracker = downloadTrackerReference.get();
                    if (liveDownloadTracker == null || liveDownloadTracker.get(key) != download) {
                        return;
                    }
                    if (verifyError != null) {
                        liveDownloadTracker.fail(key, verifyError);
                        kickOnEventLoop(liveUser,
                                "Failed to verify a server resource pack", verifyError);
                    } else {
                        liveDownloadTracker.remove(key);
                    }
                });
    }

    private static void kickOnEventLoop(final UserConnection user, final String message, final Throwable error) {
        if (!isConnectionActive(user)) {
            return;
        }
        executeOnEventLoop(user.getChannel().eventLoop(), () -> {
            if (isConnectionActive(user)) {
                BedrockProtocol.kickForIllegalState(user, message, error);
            }
        }, () -> {
        });
    }

    private static boolean isConnectionActive(final UserConnection user) {
        return user != null && user.getChannel().isActive();
    }

    private static void sendResourcePackStackFinished(final UserConnection user) {
        try {
            final PacketWrapper resourcePackClientResponse = PacketWrapper.create(ServerboundBedrockPackets.RESOURCE_PACK_CLIENT_RESPONSE, user);
            resourcePackClientResponse.write(Types.BYTE, (byte) ResourcePackResponse.ResourcePackStackFinished.getValue()); // status
            resourcePackClientResponse.write(BedrockTypes.SHORT_LE_STRING_ARRAY, new String[0]); // downloading packs
            resourcePackClientResponse.sendToServer(BedrockProtocol.class);
        } catch (Throwable e) {
            ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Failed to send ResourcePackStackFinished to Bedrock server", e);
        }
    }

    static final class BoundedByteArrayType extends Type<byte[]> {

        private final int minimumLength;
        private final int maximumLength;

        BoundedByteArrayType(final int minimumLength, final int maximumLength) {
            super(byte[].class);
            if (minimumLength < 0 || maximumLength < minimumLength) {
                throw new IllegalArgumentException("Invalid byte array bounds");
            }
            this.minimumLength = minimumLength;
            this.maximumLength = maximumLength;
        }

        @Override
        public byte[] read(final ByteBuf buffer) {
            final int length = BedrockTypes.UNSIGNED_VAR_INT.readPrimitive(buffer);
            if (length < this.minimumLength || length > this.maximumLength) {
                throw new IllegalArgumentException("Byte array length is outside the allowed range: " + length);
            }
            if (!buffer.isReadable(length)) {
                throw new IllegalArgumentException("Byte array length exceeds readable bytes: " + length
                        + " > " + buffer.readableBytes());
            }
            final byte[] value = new byte[length];
            buffer.readBytes(value);
            return value;
        }

        @Override
        public void write(final ByteBuf buffer, final byte[] value) {
            if (value.length < this.minimumLength || value.length > this.maximumLength) {
                throw new IllegalArgumentException("Byte array length is outside the allowed range: " + value.length);
            }
            BedrockTypes.UNSIGNED_VAR_INT.writePrimitive(buffer, value.length);
            buffer.writeBytes(value);
        }

    }

}
