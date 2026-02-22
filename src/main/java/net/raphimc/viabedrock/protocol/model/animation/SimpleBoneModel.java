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
package net.raphimc.viabedrock.protocol.model.animation;

import net.easecation.bedrockmotion.model.IBoneModel;
import net.easecation.bedrockmotion.model.IBoneTarget;
import org.cube.converter.model.element.Parent;
import org.cube.converter.model.impl.bedrock.BedrockGeometryModel;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.*;

/**
 * Server-side IBoneModel for ViaBedrock.
 * Built from a BedrockGeometryModel's bone hierarchy.
 * Provides world transform computation for Display Entity updates.
 */
public class SimpleBoneModel implements IBoneModel {

    private final Map<String, IBoneTarget> boneIndex = new LinkedHashMap<>();
    private final List<IBoneTarget> allBones = new ArrayList<>();
    // Bones in topological order (parents before children) for world transform computation
    private final List<SimpleBone> topoOrder = new ArrayList<>();

    public SimpleBoneModel(BedrockGeometryModel geometry) {
        final Map<String, SimpleBone> bonesByName = new LinkedHashMap<>();

        // First pass: create all bones
        for (Parent parent : geometry.getParents()) {
            final String name = parent.getName();
            final Vector3f pivot = new Vector3f(
                    parent.getPivot().getX(),
                    parent.getPivot().getY(),
                    parent.getPivot().getZ());
            final Vector3f rotation = new Vector3f(
                    parent.getRotation().getX(),
                    parent.getRotation().getY(),
                    parent.getRotation().getZ());
            final boolean hasCubes = !parent.getCubes().isEmpty();

            final SimpleBone bone = new SimpleBone(name, pivot, rotation, hasCubes);
            bonesByName.put(name.toLowerCase(Locale.ROOT), bone);
            allBones.add(bone);
            boneIndex.put(name.toLowerCase(Locale.ROOT), bone);
        }

        // Second pass: establish parent-child hierarchy
        for (Parent parent : geometry.getParents()) {
            if (parent.getParent() != null && !parent.getParent().isEmpty()) {
                final SimpleBone child = bonesByName.get(parent.getName().toLowerCase(Locale.ROOT));
                final SimpleBone parentBone = bonesByName.get(parent.getParent().toLowerCase(Locale.ROOT));
                if (child != null && parentBone != null) {
                    child.setParentBone(parentBone);
                    parentBone.getChildren().put(child.getName().toLowerCase(Locale.ROOT), child);
                }
            }
        }

        // Build topological order (parents before children)
        final Set<String> visited = new HashSet<>();
        for (SimpleBone bone : bonesByName.values()) {
            topoSort(bone, bonesByName, visited);
        }
    }

    private void topoSort(SimpleBone bone, Map<String, SimpleBone> allBones, Set<String> visited) {
        final String key = bone.getName().toLowerCase(Locale.ROOT);
        if (visited.contains(key)) return;

        // Ensure parent is visited first
        if (bone.getParentBone() != null) {
            topoSort(bone.getParentBone(), allBones, visited);
        }

        visited.add(key);
        topoOrder.add(bone);
    }

    /**
     * Get the rest pivot of a bone in Bedrock model units.
     */
    public Vector3f getRestPivot(final String boneName) {
        final IBoneTarget bone = boneIndex.get(boneName.toLowerCase(Locale.ROOT));
        if (bone instanceof SimpleBone simpleBone) {
            return simpleBone.getPivot();
        }
        return new Vector3f();
    }

    @Override
    public Map<String, IBoneTarget> getBoneIndex() {
        return boneIndex;
    }

    @Override
    public Iterable<IBoneTarget> getAllBones() {
        return allBones;
    }

    @Override
    public void resetAllBones() {
        for (IBoneTarget bone : allBones) {
            bone.resetToDefaultPose();
        }
    }

    /**
     * Compute world transforms for all bones with cubes.
     * Must be called AFTER the animation engine has written rotation/offset/scale to bones.
     * <p>
     * Bedrock bone transform: translate(pivot + offset) * rotateZYX(rotation) * translate(-pivot)
     * World transform = parent.worldMatrix * bone.localMatrix
     *
     * @return map of bone name (lowercase) -> world transform for bones that have cubes
     */
    public Map<String, WorldTransform> collectWorldTransforms() {
        final Map<String, Matrix4f> worldMatrices = new HashMap<>();
        final Map<String, WorldTransform> result = new LinkedHashMap<>();

        for (SimpleBone bone : topoOrder) {
            final Matrix4f localMatrix = computeLocalMatrix(bone);
            final Matrix4f worldMatrix;

            if (bone.getParentBone() != null) {
                final Matrix4f parentWorld = worldMatrices.get(
                        bone.getParentBone().getName().toLowerCase(Locale.ROOT));
                worldMatrix = new Matrix4f(parentWorld).mul(localMatrix);
            } else {
                worldMatrix = localMatrix;
            }

            worldMatrices.put(bone.getName().toLowerCase(Locale.ROOT), worldMatrix);

            if (bone.hasCubes()) {
                // Extract world pivot position by transforming the bone's pivot through the world matrix
                final Vector3f worldPivot = new Vector3f(bone.getPivot());
                worldMatrix.transformPosition(worldPivot);

                // Extract rotation quaternion from world matrix
                final Quaternionf worldRotation = new Quaternionf();
                worldMatrix.getNormalizedRotation(worldRotation);

                // Extract scale
                final Vector3f worldScale = new Vector3f();
                worldMatrix.getScale(worldScale);

                result.put(bone.getName().toLowerCase(Locale.ROOT),
                        new WorldTransform(worldPivot, worldRotation, worldScale));
            }
        }

        return result;
    }

    private static Matrix4f computeLocalMatrix(SimpleBone bone) {
        final Vector3f pivot = bone.getPivot();
        final Vector3f offset = bone.getOffset();
        final Vector3f rot = bone.getRotation();

        return new Matrix4f()
                .translate(pivot.x + offset.x, pivot.y + offset.y, pivot.z + offset.z)
                .rotateZYX(
                        (float) Math.toRadians(rot.z),
                        (float) Math.toRadians(rot.y),
                        (float) Math.toRadians(rot.x))
                .translate(-pivot.x, -pivot.y, -pivot.z)
                .scale(bone.getScaleX(), bone.getScaleY(), bone.getScaleZ());
    }

    /**
     * World transform for a single bone, used by Display Entity metadata updates.
     *
     * @param position world position of bone pivot (in Bedrock model units, 1/16 block)
     * @param rotation world rotation as quaternion
     * @param scale    world scale
     */
    public record WorldTransform(Vector3f position, Quaternionf rotation, Vector3f scale) {
    }

}
