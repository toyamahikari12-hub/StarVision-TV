package com.animatv.player.extra

import com.animatv.player.extension.isStreamUrl
import com.animatv.player.extension.normalize
import com.animatv.player.model.M3U
import java.math.BigInteger
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.regex.Pattern

/**
 * Parser M3U.
 *
 * Yang didukung:
 *  - #EXTINF beserta atribut: group-title, tvg-logo,
 *    http-user-agent, http-referrer / http-referer, http-origin
 *  - #EXTVLCOPT:http-user-agent / http-referrer / http-referer / http-origin
 *  - #KODIPROP:inputstream.adaptive.license_type
 *  - #KODIPROP:inputstream.adaptive.license_key
 *  - #KODIPROP:inputstream.adaptive.stream_headers (User-Agent, Referer, Origin)
 *  - #EXTGRP
 *
 * Baris opsi (#EXTVLCOPT / #KODIPROP) yang muncul SETELAH #EXTINF dan
 * SEBELUM URL dipasang ke channel tersebut. Baris opsi yang muncul
 * sebelum #EXTINF (atau setelah URL) dipasang ke channel berikutnya.
 */
class M3uTool {

    companion object {

        private val REGEX_GROUP: Pattern =
            Pattern.compile(
                ".*group-title=\"(.?|.+?)\".*",
                Pattern.CASE_INSENSITIVE
            )

        private val REGEX_NAME: Pattern =
            Pattern.compile(
                ".*,(.+?)$",
                Pattern.CASE_INSENSITIVE
            )

        // kid:key atau kid:key,kid:key (hex 32 karakter)
        private val REGEX_CLEARKEY: Regex =
            Regex("^[0-9a-fA-F]{32}:[0-9a-fA-F]{32}(,[0-9a-fA-F]{32}:[0-9a-fA-F]{32})*$")

        // Atribut di dalam baris #EXTINF
        private val ATTR_LOGO = attrPattern("tvg-logo")
        private val ATTR_UA_1 = attrPattern("http-user-agent")
        private val ATTR_UA_2 = attrPattern("user-agent")
        private val ATTR_REF_1 = attrPattern("http-referrer")
        private val ATTR_REF_2 = attrPattern("http-referer")
        private val ATTR_REF_3 = attrPattern("referrer")
        private val ATTR_REF_4 = attrPattern("referer")
        private val ATTR_ORG_1 = attrPattern("http-origin")
        private val ATTR_ORG_2 = attrPattern("origin")

        fun parse(content: String?): List<M3U> {

            val text = (content ?: "").removePrefix("\uFEFF")

            if (text.isBlank()) {
                throw M3U.ParsingException(0, "Empty stream")
            }

            val result: MutableList<M3U> = ArrayList()

            // Channel yang sedang dibaca
            var current: M3U? = null

            // true jika channel aktif sudah punya URL
            var urlSeen = false

            // Opsi yang muncul sebelum #EXTINF berikutnya
            var pending = M3U()

            var extGrp: String? = null
            var lineNumber = 0

            for (raw in text.lines()) {

                lineNumber++

                val line = raw.trim()

                if (line.isEmpty()) continue

                when {

                    line.startsWith("#EXTM3U", ignoreCase = true) -> {
                        // header, abaikan
                    }

                    // ---------------- EXTGRP ----------------
                    isExtGrp(line) -> {
                        extGrp = line.substringAfter(":", "").normalize()
                    }

                    // ---------------- EXTINF ----------------
                    isExtInf(line) -> {

                        current?.let { finish(it, result) }

                        val m = M3U()

                        val name = regexLine(line, REGEX_NAME)?.normalize()

                        m.channelName =
                            if (name.isNullOrEmpty() || name.startsWith(M3U.EXTINF)) {
                                "NO NAME"
                            } else {
                                name
                            }

                        m.groupName = regexLine(line, REGEX_GROUP)?.normalize()

                        if (m.groupName.isNullOrBlank()) {
                            m.groupName = extGrp ?: "UNCATAGORIZED"
                        }

                        m.logo = attr(line, ATTR_LOGO)

                        m.userAgent =
                            attr(line, ATTR_UA_1) ?: attr(line, ATTR_UA_2)

                        m.referrer =
                            attr(line, ATTR_REF_1)
                                ?: attr(line, ATTR_REF_2)
                                ?: attr(line, ATTR_REF_3)
                                ?: attr(line, ATTR_REF_4)

                        m.origin =
                            attr(line, ATTR_ORG_1) ?: attr(line, ATTR_ORG_2)

                        // Opsi yang datang sebelum #EXTINF menimpa atribut
                        pending.userAgent?.let { m.userAgent = it }
                        pending.referrer?.let { m.referrer = it }
                        pending.origin?.let { m.origin = it }
                        pending.licenseKey?.let { m.licenseKey = it }
                        pending.licenseType?.let { m.licenseType = it }

                        pending = M3U()
                        current = m
                        urlSeen = false
                    }

                    // ---------------- EXTVLCOPT ----------------
                    isExtVlcOpt(line) -> {

                        val target = optionTarget(current, urlSeen, pending)

                        applyVlcOpt(line, target)
                    }

                    // ---------------- KODIPROP ----------------
                    isKodi(line) -> {

                        val target = optionTarget(current, urlSeen, pending)

                        applyKodi(line, target)
                    }

                    // ---------------- STREAM URL ----------------
                    isStream(line) -> {

                        val m = current

                        if (m != null) {
                            m.streamUrl?.add(line)
                            urlSeen = true
                        }
                    }
                }
            }

            current?.let { finish(it, result) }

            return result
        }

        // =====================================
        // HELPERS
        // =====================================

        private fun optionTarget(
            current: M3U?,
            urlSeen: Boolean,
            pending: M3U
        ): M3U {

            return if (current != null && !urlSeen) current else pending
        }

        private fun finish(m: M3U, out: MutableList<M3U>) {

            if (m.streamUrl.isNullOrEmpty()) return

            resolveDrm(m)

            out.add(m)
        }

        /**
         * Menentukan nama DRM supaya cocok dengan PlayerActivity:
         *   widevine_<hash>  -> Widevine (license server URL)
         *   clearkey_<hash>  -> ClearKey (kid:key)
         */
        private fun resolveDrm(m: M3U) {

            val raw = m.licenseKey?.trim()

            if (raw.isNullOrEmpty()) {
                m.licenseKey = null
                m.licenseName = null
                return
            }

            val type = m.licenseType?.lowercase() ?: ""

            val isClearKey =
                type.contains("clearkey") ||
                    (type.isEmpty() && REGEX_CLEARKEY.matches(raw))

            if (isClearKey) {

                m.licenseKey = raw
                m.licenseName = "clearkey_" + md5(raw)

            } else {

                // Format Kodi: url|header|body|response -> ambil URL saja
                val url = raw.substringBefore("|").trim()

                val prefix =
                    if (type.contains("playready")) "playready_" else "widevine_"

                m.licenseKey = url
                m.licenseName = prefix + md5(url)
            }
        }

        private fun applyVlcOpt(line: String, target: M3U) {

            val body = line.substringAfter(":", "")
            val key = body.substringBefore("=").trim().lowercase()
            val value = cleanValue(body.substringAfter("=", ""))

            if (value.isEmpty()) return

            when (key) {
                "http-user-agent" -> target.userAgent = value
                "http-referrer", "http-referer" -> target.referrer = value
                "http-origin" -> target.origin = value
            }
        }

        private fun applyKodi(line: String, target: M3U) {

            val body = line.substringAfter(":", "")
            val key = body.substringBefore("=").trim().lowercase()
            val value = body.substringAfter("=", "").trim()

            if (value.isEmpty()) return

            when {

                key.endsWith("license_type") ->
                    target.licenseType = value

                key.endsWith("license_key") ->
                    target.licenseKey = value

                key.endsWith("stream_headers") ||
                    key.endsWith("manifest_headers") ->
                    applyKodiHeaders(value, target)
            }
        }

        // Format: User-Agent=xxx&Referer=yyy&Origin=zzz (URL-encoded)
        private fun applyKodiHeaders(value: String, target: M3U) {

            for (pair in value.split("&")) {

                val k = pair.substringBefore("=").trim().lowercase()
                val rawValue = pair.substringAfter("=", "")

                val decoded =
                    try {
                        URLDecoder.decode(rawValue, "UTF-8")
                    } catch (e: Exception) {
                        rawValue
                    }

                val v = cleanValue(decoded)

                if (v.isEmpty()) continue

                when (k) {
                    "user-agent" -> target.userAgent = v
                    "referer", "referrer" -> target.referrer = v
                    "origin" -> target.origin = v
                }
            }
        }

        private fun cleanValue(value: String): String {
            return value.trim().trim('"', '\'').trim()
        }

        // Cocokkan name="value" atau name=value pada baris #EXTINF.
        // (?<![\w-]) supaya "referrer" tidak cocok di dalam "http-referrer".
        private fun attrPattern(name: String): Pattern {
            return Pattern.compile(
                "(?<![\\w-])" + Pattern.quote(name) +
                    "=(?:\"([^\"]*)\"|([^\\s\",]+))",
                Pattern.CASE_INSENSITIVE
            )
        }

        private fun attr(line: String, pattern: Pattern): String? {

            val matcher = pattern.matcher(line)

            if (!matcher.find()) return null

            val value = matcher.group(1) ?: matcher.group(2)

            return value?.trim()?.ifEmpty { null }
        }

        // =====================================
        // DETECTION
        // =====================================

        private fun isExtVlcOpt(line: String): Boolean {
            return line.startsWith(M3U.EXTVLCOPT, ignoreCase = true)
        }

        private fun isExtGrp(line: String): Boolean {
            return line.startsWith(M3U.EXTGRP, ignoreCase = true)
        }

        private fun isExtInf(line: String): Boolean {
            return line.startsWith(M3U.EXTINF, ignoreCase = true)
        }

        private fun isKodi(line: String): Boolean {
            return line.startsWith(M3U.KODIPROP, ignoreCase = true)
        }

        private fun isStream(line: String): Boolean {
            return line.isStreamUrl()
        }

        private fun regexLine(line: String, pattern: Pattern): String? {

            val matcher = pattern.matcher(line)

            return if (matcher.matches()) matcher.group(1) else null
        }

        // =====================================
        // MD5
        // =====================================

        private fun md5(input: String): String {

            val md = MessageDigest.getInstance("MD5")

            return BigInteger(1, md.digest(input.toByteArray()))
                .toString(16)
                .padStart(32, '0')
        }
    }
}
