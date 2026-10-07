package com.kodari.skinrestorer

import com.google.gson.JsonParser
import org.bukkit.profile.PlayerTextures
import java.net.URI
import java.net.InetAddress
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.util.LinkedHashMap
import java.time.Duration
import java.util.Base64
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CompletionException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

class MojangSkinService {
    private data class CachedSkin(val skin: SkinData, val expiresAt: Long)

    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()
    private val cache = LinkedHashMap<String, CachedSkin>(16, 0.75f, true)
    private val inFlight = ConcurrentHashMap<String, CompletableFuture<SkinData>>()
    private val retryAfter = AtomicLong(0)
    private val networkExecutor = ThreadPoolExecutor(
        NETWORK_THREADS,
        NETWORK_THREADS,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(NETWORK_QUEUE_SIZE),
        ThreadFactory { task -> Thread(task, "SkinRestorer-IO").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy()
    )

    fun submit(fetch: () -> SkinData): CompletableFuture<SkinData> =
        CompletableFuture.supplyAsync({ fetch() }, networkExecutor)

    fun shutdown() {
        networkExecutor.shutdownNow()
    }

    fun fetchByName(name: String): SkinData {
        require(PLAYER_NAME_PATTERN.matches(name)) { "That is not a valid Minecraft player name." }
        val key = name.lowercase(Locale.ROOT)
        val now = System.nanoTime()
        synchronized(cache) {
            cache[key]?.let { cached ->
                if (cached.expiresAt > now) return cached.skin
                cache.remove(key)
            }
        }

        val request = CompletableFuture<SkinData>()
        val existing = inFlight.putIfAbsent(key, request)
        if (existing != null) {
            return try {
                existing.join()
            } catch (exception: CompletionException) {
                throw (exception.cause as? Exception ?: exception)
            }
        }

        try {
            val skin = fetchByNameUncached(name)
            synchronized(cache) {
                cache[key] = CachedSkin(skin, System.nanoTime() + CACHE_NANOS)
                while (cache.size > MAX_CACHED_SKINS) {
                    cache.entries.iterator().run {
                        next()
                        remove()
                    }
                }
            }
            request.complete(skin)
            return skin
        } catch (exception: Throwable) {
            request.completeExceptionally(exception)
            throw exception
        } finally {
            inFlight.remove(key, request)
        }
    }

    private fun fetchByNameUncached(name: String): SkinData {
        val profileJson = mojangGet("https://api.mojang.com/users/profiles/minecraft/$name")
        val profileId = JsonParser.parseString(profileJson).asJsonObject.get("id")?.asString
            ?: throw IllegalStateException("Mojang did not return a profile for $name.")
        val sessionJson = mojangGet("https://sessionserver.mojang.com/session/minecraft/profile/$profileId?unsigned=false")
        val properties = JsonParser.parseString(sessionJson).asJsonObject.getAsJsonArray("properties")
        val encoded = properties.firstOrNull { it.asJsonObject.get("name")?.asString == "textures" }
            ?.asJsonObject?.get("value")?.asString
            ?: throw IllegalStateException("No skin texture was returned for $name.")
        val textureJson = String(Base64.getDecoder().decode(encoded), Charsets.UTF_8)
        val texture = JsonParser.parseString(textureJson).asJsonObject
            .getAsJsonObject("textures")?.getAsJsonObject("SKIN")
            ?: throw IllegalStateException("No skin texture was returned for $name.")
        val textureUrl = texture.get("url")?.asString
            ?: throw IllegalStateException("No skin texture URL was returned for $name.")
        val model = texture.getAsJsonObject("metadata")?.get("model")?.asString
            ?: PlayerTextures.SkinModel.CLASSIC.name.lowercase(Locale.ROOT)
        val signature = properties.firstOrNull { it.asJsonObject.get("name")?.asString == "textures" }
            ?.asJsonObject?.get("signature")?.asString
        return SkinData(
            textureUrl = textureUrl,
            model = model,
            sourceName = name,
            textureValue = encoded,
            textureSignature = signature
        )
    }

    fun fetchRandom(): SkinData {
        val name = RANDOM_NAMES[ThreadLocalRandom.current().nextInt(RANDOM_NAMES.size)]
        return fetchByName(name)
    }

    fun fetchUrl(url: String, model: String): SkinData {
        val uri = URI(url.trim())
        require(uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) {
            "Skin URLs must use HTTP or HTTPS."
        }
        val host = uri.host?.lowercase(Locale.ROOT)
        require(host != null && uri.rawUserInfo == null && !host.endsWith(".localhost") &&
            !host.endsWith(".local") && host != "localhost") { "That is not a valid skin URL." }
        require(model.equals("classic", true) || model.equals("slim", true)) {
            "Model must be classic or slim."
        }
        val addresses = InetAddress.getAllByName(host)
        require(addresses.isNotEmpty() && addresses.none {
            it.isAnyLocalAddress || it.isLoopbackAddress || it.isLinkLocalAddress ||
                it.isSiteLocalAddress || it.isMulticastAddress
        }) { "Skin URLs must use a public host." }

        val request = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(15))
            .header("User-Agent", USER_AGENT)
            .GET()
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
        val image = response.body().use { body ->
            if (response.statusCode() !in 200..299) {
                throw IllegalStateException("The skin URL returned HTTP ${response.statusCode()}.")
            }
            body.readNBytes(MAX_IMAGE_BYTES + 1)
        }
        require(image.size <= MAX_IMAGE_BYTES && isSkinPng(image)) {
            "The URL must return a PNG skin sized 64x64 or 64x32."
        }
        return SkinData(uri.toString(), model.lowercase(Locale.ROOT), sourceUrl = uri.toString())
    }

    private fun mojangGet(url: String): String {
        val now = System.currentTimeMillis()
        val backoffUntil = retryAfter.get()
        if (now < backoffUntil) {
            throw IllegalStateException("Mojang is rate-limiting requests; retry in ${(backoffUntil - now + 999) / 1000} seconds.")
        }

        val request = HttpRequest.newBuilder(URI(url))
            .timeout(Duration.ofSeconds(10))
            .header("User-Agent", USER_AGENT)
            .GET()
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() == 429) {
            val now = System.currentTimeMillis()
            val retryMillis = response.headers().firstValue("Retry-After").orElse(null)
                ?.let { header ->
                    header.toLongOrNull()?.let { it.coerceIn(1, 3600) * 1000 }
                        ?: runCatching {
                            (ZonedDateTime.parse(header, DateTimeFormatter.RFC_1123_DATE_TIME)
                                .toInstant().toEpochMilli() - now).coerceIn(1000, 3_600_000)
                        }.getOrNull()
                } ?: DEFAULT_BACKOFF_SECONDS * 1000
            val retrySeconds = (retryMillis + 999) / 1000
            retryAfter.updateAndGet { maxOf(it, now + retryMillis) }
            throw IllegalStateException("Mojang rate-limited the request; retry in $retrySeconds seconds.")
        }
        if (response.statusCode() !in 200..299) {
            throw IllegalStateException("Mojang returned HTTP ${response.statusCode()}.")
        }
        return response.body()
    }

    private fun isSkinPng(bytes: ByteArray): Boolean {
        val pngSignature = byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10)
        if (bytes.size < 24 || !bytes.copyOfRange(0, 8).contentEquals(pngSignature)) return false
        val header = ByteBuffer.wrap(bytes, 16, 8)
        val width = header.int
        val height = header.int
        return width == 64 && (height == 64 || height == 32)
    }

    companion object {
        private val PLAYER_NAME_PATTERN = Regex("[A-Za-z0-9_]{1,16}")
        private val CACHE_NANOS = TimeUnit.DAYS.toNanos(7)
        private const val MAX_CACHED_SKINS = 2048
        private const val DEFAULT_BACKOFF_SECONDS = 60L
        private const val MAX_IMAGE_BYTES = 10 * 1024 * 1024
        private const val NETWORK_THREADS = 4
        private const val NETWORK_QUEUE_SIZE = 128
        private const val USER_AGENT = "KodariSkinRestorer/1.0"
        private val RANDOM_NAMES = listOf("Notch", "jeb_", "Dinnerbone", "Grumm", "Searge", "MHF_Creeper")
    }
}