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
package org.codice.ddf.security.file.token.storage;

import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;
import static org.apache.http.HttpStatus.SC_INTERNAL_SERVER_ERROR;
import static org.apache.http.HttpStatus.SC_OK;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.hash.Hashing;
import ddf.security.encryption.crypter.Crypter;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.IOUtils;
import org.codice.ddf.security.token.storage.api.TokenInformation;
import org.codice.ddf.security.token.storage.api.TokenInformation.TokenEntry;
import org.codice.ddf.security.token.storage.api.TokenStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FileSystemTokenStorage implements TokenStorage {

  private static final Logger LOGGER = LoggerFactory.getLogger(FileSystemTokenStorage.class);
  private static final String KARAF_HOME = "karaf.home";

  private final Map<String, Map<String, Object>> stateMap;
  private Crypter crypter;
  private Path baseDirectory;

  public FileSystemTokenStorage() {
    stateMap = new ConcurrentHashMap<>();
    crypter =
        AccessController.doPrivileged(
            (PrivilegedAction<Crypter>) () -> new Crypter("token-storage"));
  }

  @VisibleForTesting
  FileSystemTokenStorage(Crypter crypter) {
    stateMap = new ConcurrentHashMap<>();
    this.crypter = crypter;
  }

  /**
   * @return a map containing state UUIDs with their corresponding ID, source ID, discovery URL,
   *     client ID, and secret information. Clears out all the expired state information before
   *     returning the map.
   */
  @Override
  public Map<String, Map<String, Object>> getStateMap() {
    stateMap
        .entrySet()
        .stream()
        .filter(
            entry -> (long) entry.getValue().get(EXPIRES_AT) - Instant.now().getEpochSecond() <= 0)
        .forEach(entry -> stateMap.remove(entry.getKey()));

    return stateMap;
  }

  /**
   * Stores a user's or client's access token. If it's a new user or client, creates a new entry.
   * Otherwise, updates the existing data.
   *
   * @param id the ID used to store the tokens
   * @param sourceId the ID of the source the tokens are going to be used against
   * @param accessToken the access token
   * @param refreshToken the refresh token
   * @param discoveryUrl the metadata url of the OAuth provider protecting the source
   * @return an HTTP status code
   */
  @Override
  public int create(
      String id, String sourceId, String accessToken, String refreshToken, String discoveryUrl) {
    LOGGER.trace("Create: Updating a Token Storage entry.");
    String fileName;
    String json;

    TokenInformation tokenInformation = read(id);

    if (tokenInformation == null) {
      // create new entry
      fileName = hashId(id);
      json = TokenInformationUtil.getJson(sourceId, accessToken, refreshToken, discoveryUrl);
    } else {
      // update existing
      fileName = tokenInformation.getId();
      json =
          TokenInformationUtil.getJson(
              tokenInformation.getTokenJson(), sourceId, accessToken, refreshToken, discoveryUrl);
    }

    Path contentItemPath = Paths.get(baseDirectory.toAbsolutePath().toString(), fileName);
    return writeToFile(contentItemPath, json);
  }

  /**
   * Reads tokens associated with the given ID
   *
   * @param id the ID used to retrieve tokens
   * @return a {@link TokenInformation} filled with tokens
   */
  @Override
  public TokenInformation read(String id) {
    LOGGER.trace("Read: Retrieving a Token Storage entry.");

    String hash = hashId(id);
    Path contentItemPath = Paths.get(baseDirectory.toAbsolutePath().toString(), hash);

    String json = readFromFile(contentItemPath);
    if (json == null) {
      return null;
    }
    return TokenInformationUtil.fromJson(hash, json);
  }

  /**
   * Reads tokens associated with the given ID for the specified source
   *
   * @param id the ID used to retrieve tokens
   * @param sourceId the source ID the tokens correspond to
   * @return a {@link TokenEntry} filled with tokens
   */
  @Override
  public TokenEntry read(String id, String sourceId) {
    TokenInformation tokenInformation = read(id);
    if (tokenInformation == null) {
      return null;
    }

    return tokenInformation.getTokenEntries().get(sourceId);
  }

  /**
   * Checks if tokens are available
   *
   * @param id the ID used to check if tokens are available
   * @param sourceId the source ID the tokens correspond to
   * @return true if the tokens for the given ID and source are available and false if they are not
   */
  @Override
  public boolean isAvailable(String id, String sourceId) {
    TokenEntry tokenEntry = read(id, sourceId);
    return tokenEntry != null && tokenEntry.getAccessToken() != null;
  }

  /**
   * Removes tokens associated with the given ID
   *
   * @param id the ID associated with the tokens
   * @return an HTTP status code
   */
  @Override
  public int delete(String id) {
    LOGGER.trace("Delete: Deleting Token Storage.");

    TokenInformation tokenInformation = read(id);
    if (tokenInformation == null) {
      return SC_OK;
    }

    Path contentItemPath =
        Paths.get(baseDirectory.toAbsolutePath().toString(), tokenInformation.getId());

    // delete file
    try {
      Files.delete(contentItemPath);
      return SC_OK;
    } catch (IOException e) {
      LOGGER.debug("Error deleting token file.", e);
      return SC_INTERNAL_SERVER_ERROR;
    }
  }

  /**
   * Removes tokens associated with the given ID for the specified source
   *
   * @param id the ID associated with the tokens
   * @param sourceId the ID for the source the tokens are going to be used against
   * @return an HTTP status code
   */
  @Override
  public int delete(String id, String sourceId) {
    LOGGER.trace("Delete: Deleting a Token Storage entry.");

    TokenInformation tokenInformation = read(id);
    if (tokenInformation == null) {
      return SC_OK;
    }

    TokenEntry tokenEntry = tokenInformation.getTokenEntries().get(sourceId);
    if (tokenEntry == null) {
      return SC_OK;
    }

    Path contentItemPath =
        Paths.get(baseDirectory.toAbsolutePath().toString(), tokenInformation.getId());
    if (tokenInformation.getTokenEntries().size() == 1) {
      // delete file
      try {
        Files.delete(contentItemPath);
        return SC_OK;
      } catch (IOException e) {
        LOGGER.debug("Error deleting token file.", e);
        return SC_INTERNAL_SERVER_ERROR;
      }
    }

    String json = TokenInformationUtil.removeTokens(tokenInformation.getTokenJson(), sourceId);
    return writeToFile(contentItemPath, json);
  }

  private int writeToFile(Path contentItemPath, String tokenJson) {
    try (InputStream inputStream = new ByteArrayInputStream(tokenJson.getBytes());
        InputStream encryptedInputStream = crypter.encrypt(inputStream)) {

      int available = encryptedInputStream.available();
      long copySize = Files.copy(encryptedInputStream, contentItemPath, REPLACE_EXISTING);

      if (copySize < available) {
        LOGGER.warn("Unable to write full token content to file.");
        return SC_INTERNAL_SERVER_ERROR;
      }
    } catch (IOException e) {
      LOGGER.debug("Error updating token file.", e);
      return SC_INTERNAL_SERVER_ERROR;
    }
    return SC_OK;
  }

  private String readFromFile(Path contentItemPath) {
    String json = null;
    try (InputStream contentInputStream = Files.newInputStream(contentItemPath);
        InputStream decryptedInputStream = crypter.decrypt(contentInputStream)) {

      json = IOUtils.toString(decryptedInputStream, StandardCharsets.UTF_8);
    } catch (IOException e) {
      LOGGER.debug("Error reading token file.", e);
    }
    return json;
  }

  /** @return a hash of the given ID */
  private String hashId(String id) {
    return Hashing.sha256().hashString(id, StandardCharsets.UTF_8).toString();
  }

  public void setBaseDirectory(String baseDirectory) throws IOException {
    // Get path to base directory
    String normalized = FilenameUtils.normalize(baseDirectory);
    String path;
    try {
      path = Paths.get(normalized).toFile().getCanonicalPath();
    } catch (InvalidPathException e) {
      path = System.getProperty(KARAF_HOME);
    }

    Path directoryPath = Paths.get(path);

    // Make sure directory exists
    if (!directoryPath.toFile().exists()) {
      directoryPath = Files.createDirectories(directoryPath);
    }

    this.baseDirectory = directoryPath;
  }
}
