package com.animatv.player.extra

import com.animatv.player.extension.isStreamUrl
import com.animatv.player.extension.normalize
import com.animatv.player.model.M3U
import java.io.BufferedReader
import java.io.IOException
import java.io.StringReader
import java.math.BigInteger
import java.security.MessageDigest
import java.util.regex.Matcher
import java.util.regex.Pattern

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

        private val REGEX_KODI: Pattern =
            Pattern.compile(
                ".*license_key=(.+?)$",
                Pattern.CASE_INSENSITIVE
            )

        private val REGEX_GRP: Pattern =
            Pattern.compile(
                ".*:(.+?)$",
                Pattern.CASE_INSENSITIVE
            )

        // User-Agent dari EXT-VLC
        private val REGEX_USER_AGENT: Pattern =
            Pattern.compile(
                ".*http-user-agent=(.+?)$",
                Pattern.CASE_INSENSITIVE
            )

        // Referer dari EXT-VLC
        private val REGEX_REFERRER: Pattern =
            Pattern.compile(
                ".*http-referrer=(.+?)$",
                Pattern.CASE_INSENSITIVE
            )

        // Beberapa playlist memakai "referer", bukan "referrer"
        private val REGEX_REFERER: Pattern =
            Pattern.compile(
                ".*http-referer=(.+?)$",
                Pattern.CASE_INSENSITIVE
            )

        fun parse(content: String?): List<M3U> {

            val result: MutableList<M3U> = ArrayList()

            var lineNumber = 0
            var line: String?

            try {

                val buffer = BufferedReader(
                    StringReader(content ?: "")
                )

                line = buffer.readLine()

                if (line == null) {
                    throw M3U.ParsingException(
                        0,
                        "Empty stream"
                    )
                }

                lineNumber++

                var m3u = M3U()

                var userAgent: String? = null
                var referer: String? = null

                var extGrp: String? = null

                while (
                    buffer.readLine().also {
                        line = it
                    } != null
                ) {

                    lineNumber++

                    when {

                        // ==============================
                        // EXT VLC OPTIONS
                        // ==============================
                        isExtVlcOpt(line) -> {

                            val ua =
                                regexUserAgent(line)

                            val ref =
                                regexReferrer(line)
                                    ?: regexReferer(line)

                            if (!ua.isNullOrBlank()) {
                                userAgent = ua.trim()
                            }

                            if (!ref.isNullOrBlank()) {
                                referer = ref.trim()
                            }
                        }

                        // ==============================
                        // EXTGRP
                        // ==============================
                        isExtGrp(line) -> {

                            extGrp =
                                regexGrp(line)
                                    ?.normalize()
                        }

                        // ==============================
                        // EXTINF
                        // ==============================
                        isExtInf(line) -> {

                            // Simpan channel sebelumnya
                            if (!m3u.channelName.isNullOrEmpty()) {
                                result.add(m3u)
                            }

                            m3u = M3U()

                            // Nama channel
                            m3u.channelName =
                                regexCh(line)
                                    ?.normalize()

                            // Group
                            m3u.groupName =
                                regexTitle(line)
                                    ?.normalize()

                            if (
                                m3u.channelName.isNullOrEmpty() ||
                                m3u.channelName?.startsWith(
                                    M3U.EXTINF
                                ) == true
                            ) {
                                m3u.channelName = "NO NAME"
                            }

                            if (m3u.groupName.isNullOrBlank()) {
                                m3u.groupName =
                                    extGrp ?: "UNCATAGORIZED"
                            }
                        }

                        // ==============================
                        // KODI DRM
                        // ==============================
                        isKodi(line) -> {

                            m3u.licenseKey =
                                regexKodi(line)

                            m3u.licenseName =
                                md5(
                                    regexKodi(line).toString()
                                )
                        }

                        // ==============================
                        // STREAM URL
                        // ==============================
                        isStream(line) -> {

                            val streamUrl =
                                line?.trim()
                                    ?: continue

                            var urlWithHeaders =
                                streamUrl

                            // User-Agent
                            if (!userAgent.isNullOrBlank()) {

                                urlWithHeaders +=
                                    "|User-Agent=$userAgent"
                            }

                            // Referer
                            if (!referer.isNullOrBlank()) {

                                urlWithHeaders +=
                                    "|referer=$referer"
                            }

                            m3u.streamUrl?.add(
                                urlWithHeaders
                            )
                        }
                    }
                }

                // Simpan channel terakhir
                if (!m3u.channelName.isNullOrEmpty()) {
                    result.add(m3u)
                }

                buffer.close()

            } catch (e: IOException) {

                throw M3U.ParsingException(
                    lineNumber,
                    "Cannot read file",
                    e
                )
            }

            return result
        }

        // ==============================
        // DETECTION
        // ==============================

        private fun isExtVlcOpt(
            line: String?
        ): Boolean {

            return line?.startsWith(
                M3U.EXTVLCOPT,
                ignoreCase = true
            ) == true
        }

        private fun isExtGrp(
            line: String?
        ): Boolean {

            return line?.startsWith(
                M3U.EXTGRP,
                ignoreCase = true
            ) == true
        }

        private fun isExtInf(
            line: String?
        ): Boolean {

            return line?.startsWith(
                M3U.EXTINF,
                ignoreCase = true
            ) == true
        }

        private fun isKodi(
            line: String?
        ): Boolean {

            return line?.startsWith(
                M3U.KODIPROP,
                ignoreCase = true
            ) == true &&
                    line.contains(
                        "license_key",
                        ignoreCase = true
                    )
        }

        private fun isStream(
            line: String?
        ): Boolean {

            return line?.isStreamUrl() == true
        }

        // ==============================
        // REGEX
        // ==============================

        private fun regexCh(
            line: String?
        ): String? {

            return regexLine(
                line,
                REGEX_NAME
            )
        }

        private fun regexTitle(
            line: String?
        ): String? {

            return regexLine(
                line,
                REGEX_GROUP
            )
        }

        private fun regexKodi(
            line: String?
        ): String? {

            return regexLine(
                line,
                REGEX_KODI
            )
        }

        private fun regexGrp(
            line: String?
        ): String? {

            return regexLine(
                line,
                REGEX_GRP
            )
        }

        private fun regexUserAgent(
            line: String?
        ): String? {

            return regexLine(
                line,
                REGEX_USER_AGENT
            )
        }

        private fun regexReferrer(
            line: String?
        ): String? {

            return regexLine(
                line,
                REGEX_REFERRER
            )
        }

        private fun regexReferer(
            line: String?
        ): String? {

            return regexLine(
                line,
                REGEX_REFERER
            )
        }

        private fun regexLine(
            line: String?,
            pattern: Pattern
        ): String? {

            val matcher: Matcher =
                pattern.matcher(
                    line.toString()
                )

            return if (matcher.matches()) {
                matcher.group(1)
            } else {
                null
            }
        }

        // ==============================
        // MD5
        // ==============================

        private fun md5(
            input: String
        ): String {

            val md =
                MessageDigest.getInstance("MD5")

            return BigInteger(
                1,
                md.digest(
                    input.toByteArray()
                )
            )
                .toString(16)
                .padStart(32, '0')
        }
    }
}
