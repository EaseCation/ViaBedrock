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
package net.raphimc.viabedrock.experimental.model;

import net.raphimc.viabedrock.protocol.model.Position3f;

public final class PlayerAuthInputContext {

    private Position3f position;
    private Position3f delta;
    private float vehiclePitch;
    private float vehicleYaw;
    private Long predictedVehicleUniqueId;

    public PlayerAuthInputContext(final Position3f position, final Position3f delta) {
        this.position = position;
        this.delta = delta;
    }

    public Position3f position() {
        return this.position;
    }

    public void setPosition(final Position3f position) {
        this.position = position;
    }

    public Position3f delta() {
        return this.delta;
    }

    public void setDelta(final Position3f delta) {
        this.delta = delta;
    }

    public boolean hasPredictedVehicle() {
        return this.predictedVehicleUniqueId != null;
    }

    public float vehiclePitch() {
        return this.vehiclePitch;
    }

    public float vehicleYaw() {
        return this.vehicleYaw;
    }

    public long predictedVehicleUniqueId() {
        return this.predictedVehicleUniqueId;
    }

    public void setPredictedVehicle(final long predictedVehicleUniqueId, final float vehiclePitch, final float vehicleYaw) {
        this.predictedVehicleUniqueId = predictedVehicleUniqueId;
        this.vehiclePitch = vehiclePitch;
        this.vehicleYaw = vehicleYaw;
    }

}
