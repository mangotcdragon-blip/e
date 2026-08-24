/*
 * WorldPainter, a graphical map generator for Minecraft. Copyright (C) 2011-2015  pepsoft.org
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public
 * License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later
 * version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with this program. If not, see
 * <http://www.gnu.org/licenses/>.
 */
package org.pepsoft.worldpainter.gpu;

/**
 * Thrown when an OpenCL operation fails. Callers on the export path are expected to catch this, disable GPU
 * acceleration for the remainder of the session and continue on the CPU: a broken driver must never fail an export.
 */
public class GpuException extends RuntimeException {
    public GpuException(String message) {
        super(message);
        this.errorCode = 0;
    }

    public GpuException(String message, Throwable cause) {
        super(message, cause);
        this.errorCode = 0;
    }

    public GpuException(String message, int errorCode) {
        super(message + " (OpenCL error " + errorCode + ": " + CLErrors.describe(errorCode) + ')');
        this.errorCode = errorCode;
    }

    /**
     * The OpenCL error code that caused this exception, or 0 if it was not caused by a specific OpenCL error.
     */
    public int getErrorCode() {
        return errorCode;
    }

    private final int errorCode;

    private static final long serialVersionUID = 1L;
}
