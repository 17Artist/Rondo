/*
 * Copyright 2026 17Artist
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package priv.seventeen.artist.rondo.config

import org.bukkit.configuration.file.YamlConfiguration
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

internal object MySQLConfigMigration {
    private const val PARAMETERS = "storage.mysql.parameters"
    private const val SSL_MODE = "storage.mysql.ssl-mode"
    private const val PUBLIC_KEY_RETRIEVAL = "storage.mysql.allow-public-key-retrieval"
    private val SSL_MODES = setOf("DISABLED", "PREFERRED", "REQUIRED", "VERIFY_CA", "VERIFY_IDENTITY")

    /** Updates published config.yml files before Blink discards their former MySQL fields. */
    fun migrate(file: Path): Path? {
        if (!Files.exists(file)) return null

        val original = Files.readString(file, StandardCharsets.UTF_8)
        val config = YamlConfiguration().apply {
            options().parseComments(true)
            loadFromString(original)
        }
        require(!config.isSet("storage") || config.isConfigurationSection("storage")) {
            "storage 必须是配置分组"
        }
        if (!config.getString("storage.type", "sqlite").equals("mysql", ignoreCase = true)) return null
        require(!config.isSet("storage.mysql") || config.isConfigurationSection("storage.mysql")) {
            "storage.mysql 必须是配置分组"
        }

        val hasParameters = config.isSet(PARAMETERS)
        val hasLegacyFields = config.isSet(SSL_MODE) || config.isSet(PUBLIC_KEY_RETRIEVAL)
        if (hasParameters) {
            require(config.get(PARAMETERS) is String) { "storage.mysql.parameters 必须是字符串" }
        }
        if (hasParameters && !hasLegacyFields) return null

        if (!hasParameters) {
            val rawMode = config.get(SSL_MODE) ?: "PREFERRED"
            require(rawMode is String) { "旧版 storage.mysql.ssl-mode 必须是字符串" }
            val sslMode = rawMode.uppercase()
            require(sslMode in SSL_MODES) { "旧版 storage.mysql.ssl-mode 值无效" }

            val rawPublicKeyRetrieval = config.get(PUBLIC_KEY_RETRIEVAL) ?: false
            require(rawPublicKeyRetrieval is Boolean) {
                "旧版 storage.mysql.allow-public-key-retrieval 必须是 true 或 false"
            }
            // Keep the published TLS and public-key settings; timestamp handling is independent of JDBC time-zone options.
            config.set(
                PARAMETERS,
                "sslMode=$sslMode&allowPublicKeyRetrieval=$rawPublicKeyRetrieval" +
                    "&characterEncoding=UTF-8&useUnicode=true"
            )
        }
        config.set(SSL_MODE, null)
        config.set(PUBLIC_KEY_RETRIEVAL, null)

        val updated = config.saveToString()
        val backup = nextBackupPath(file)
        Files.copy(file, backup)
        val temporary = Files.createTempFile(file.parent, ".rondo-config-", ".tmp")
        try {
            Files.writeString(temporary, updated, StandardCharsets.UTF_8)
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
        return backup
    }

    private fun nextBackupPath(file: Path): Path {
        val baseName = "${file.fileName}.pre-jdbc-parameters.bak"
        var backup = file.resolveSibling(baseName)
        var suffix = 1
        while (Files.exists(backup)) {
            backup = file.resolveSibling("$baseName.$suffix")
            suffix++
        }
        return backup
    }
}
