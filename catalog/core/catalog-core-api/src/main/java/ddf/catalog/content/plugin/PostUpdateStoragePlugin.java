/**
 * Copyright (c) Codice Foundation
 *
 * <p>This is free software: you can redistribute it and/or modify it under the terms of the GNU
 * Lesser General Public License as published by the Free Software Foundation, either version 3 of
 * the License, or any later version.
 *
 * <p>This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details. A copy of the GNU Lesser General Public
 * License is distributed along with this program and can be found at
 * <http://www.gnu.org/licenses/lgpl.html>.
 */
package ddf.catalog.content.plugin;

import ddf.catalog.content.operation.UpdateStorageResponse;
import ddf.catalog.plugin.PluginExecutionException;

/**
 * Services implementing this interface are called immediately after an item is updated in the
 * content repository.
 *
 * <p>
 *
 * <p><b> This code is experimental. While this interface is functional and tested, it may change or
 * be removed in a future version of the library. </b>
 */
public interface PostUpdateStoragePlugin {
  /**
   * Processes the {@link ddf.catalog.content.operation.UpdateStorageResponse}.
   *
   * @param input the {@code UpdateStorageResponse} to process
   * @return the processed {@code UpdateStorageResponse} to pass to the next {@link
   *     PostUpdateStoragePlugin}
   * @throws PluginExecutionException if an error occurs during processing
   */
  UpdateStorageResponse process(UpdateStorageResponse input) throws PluginExecutionException;
}
