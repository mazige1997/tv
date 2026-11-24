package xyz.doikki.videoplayer.exo;

import android.content.Context;
import android.net.Uri;
import android.text.TextUtils;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.database.ExoDatabaseProvider;
import com.google.android.exoplayer2.ext.rtmp.RtmpDataSourceFactory;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.source.ProgressiveMediaSource;
import com.google.android.exoplayer2.source.dash.DashMediaSource;
import com.google.android.exoplayer2.source.hls.HlsMediaSource;
import com.google.android.exoplayer2.source.rtsp.RtspMediaSource;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.cache.Cache;
import com.google.android.exoplayer2.upstream.cache.CacheDataSource;
import com.google.android.exoplayer2.upstream.cache.LeastRecentlyUsedCacheEvictor;
import com.google.android.exoplayer2.upstream.cache.SimpleCache;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

import okhttp3.OkHttpClient;

public final class ExoMediaSourceHelper {
    public static final String HEADER_FORMAT = "TVBox-Format";

    private static ExoMediaSourceHelper sInstance;

    private final Context mAppContext;
    private OkHttpDataSource.Factory mHttpDataSourceFactory;
    private Cache mCache;
    private OkHttpClient mClient;
    // 边下边播:由上层(ExoPlayer.kt)根据设置项在每次 setDataSource 前设置,
    // 开启后 http(s) 媒体走 CacheDataSource(本地 SimpleCache,LRU 512M),实现边下边播。
    private volatile boolean mCacheEnabled;

    // 缓存诊断事件(供上层写入诊断日志):记录 CacheDataSource 的缓存命中/忽略原因。
    // reason: 0=CACHE_IGNORED_REASON_ERROR, 1=CACHE_IGNORED_REASON_UNSET_LENGTH。
    public static volatile String sCacheEvent = "none";

    public boolean isCacheEnabled() {
        return mCacheEnabled;
    }

    public void setCacheEnabled(boolean cacheEnabled) {
        mCacheEnabled = cacheEnabled;
    }

    private ExoMediaSourceHelper(Context context) {
        mAppContext = context.getApplicationContext();
    }

    public static ExoMediaSourceHelper getInstance(Context context) {
        if (sInstance == null) {
            synchronized (ExoMediaSourceHelper.class) {
                if (sInstance == null) {
                    sInstance = new ExoMediaSourceHelper(context);
                }
            }
        }
        return sInstance;
    }

    public void setOkClient(OkHttpClient client) {
        mClient = client;
        mHttpDataSourceFactory = null;
    }

    public MediaSource getMediaSource(String uri) {
        return getMediaSource(uri, null, false);
    }

    public MediaSource getMediaSource(String uri, Map<String, String> headers) {
        return getMediaSource(uri, headers, false);
    }

    public MediaSource getMediaSource(String uri, boolean isCache) {
        return getMediaSource(uri, null, isCache);
    }

    public MediaSource getMediaSource(String uri, Map<String, String> headers, boolean isCache) {
        return getMediaSource(uri, headers, isCache, inferContentType(uri, headers));
    }

    public MediaSource getHlsMediaSource(String uri, Map<String, String> headers) {
        // 与 setDataSource 保持一致:边下边播开启时,解析失败重试 HLS 的路径同样走缓存
        return getMediaSource(uri, headers, mCacheEnabled, C.TYPE_HLS);
    }

    private MediaSource getMediaSource(String uri, Map<String, String> headers, boolean isCache, int contentType) {
        Uri contentUri = Uri.parse(uri);
        if ("rtmp".equals(contentUri.getScheme())) {
            return new ProgressiveMediaSource.Factory(new RtmpDataSourceFactory(null))
                    .createMediaSource(MediaItem.fromUri(contentUri));
        } else if ("rtsp".equals(contentUri.getScheme())) {
            return new RtspMediaSource.Factory().createMediaSource(MediaItem.fromUri(contentUri));
        }
        DataSource.Factory factory;
        if (isCache) {
            factory = getCacheDataSourceFactory();
        } else {
            factory = getDataSourceFactory();
        }
        applyHeaders(headers);
        switch (contentType) {
            case C.TYPE_DASH:
                return new DashMediaSource.Factory(factory).createMediaSource(MediaItem.fromUri(contentUri));
            case C.TYPE_HLS:
                return new HlsMediaSource.Factory(factory)
                        .setExtractorFactory(new ResyncHlsExtractorFactory())
                        .setLoadErrorHandlingPolicy(new HlsErrorHandlingPolicy())  // 切片快速重试并兼容TS同步异常，保留默认回退
                        .createMediaSource(MediaItem.fromUri(contentUri));
            default:
            case C.TYPE_OTHER:
                return new ProgressiveMediaSource.Factory(factory).createMediaSource(MediaItem.fromUri(contentUri));
        }
    }

    private int inferContentType(String fileName, Map<String, String> headers) {
        int formatType = inferFormatContentType(headers);
        if (formatType != C.TYPE_OTHER) {
            return formatType;
        }
        fileName = fileName.toLowerCase();
        if (fileName.contains(".mpd") || fileName.contains("type=mpd") || fileName.contains("type=dash") || fileName.contains("format=mpd") || fileName.contains("format=dash")) {
            return C.TYPE_DASH;
        } else if (isHlsUri(fileName)) {
            return C.TYPE_HLS;
        } else {
            return C.TYPE_OTHER;
        }
    }

    private int inferFormatContentType(Map<String, String> headers) {
        if (headers == null || !headers.containsKey(HEADER_FORMAT)) {
            return C.TYPE_OTHER;
        }
        String format = headers.get(HEADER_FORMAT);
        if (format == null) {
            return C.TYPE_OTHER;
        }
        format = format.trim().toLowerCase();
        if (format.equals("hls") || format.contains("mpegurl") || format.contains("m3u8")) {
            return C.TYPE_HLS;
        }
        if (format.equals("dash") || format.equals("mpd") || format.contains("dash+xml")) {
            return C.TYPE_DASH;
        }
        return C.TYPE_OTHER;
    }

    private boolean isHlsUri(String uri) {
        if (isAudioUri(uri)) {
            return false;
        }
        if (uri.contains("m3u8") || uri.contains("type=hls") || uri.contains("format=hls")) {
            return true;
        }
        Uri parsedUri = Uri.parse(uri);
        String path = parsedUri.getPath();
        if (path == null) {
            return false;
        }
        path = path.toLowerCase();
        return path.endsWith("/live.php") || path.contains("/live/");
    }

    private boolean isAudioUri(String uri) {
        Uri parsedUri = Uri.parse(uri);
        String path = parsedUri.getPath();
        if (path == null) {
            path = uri;
        }
        path = path.toLowerCase();
        return path.endsWith(".mp3")
                || path.endsWith(".m4a")
                || path.endsWith(".aac")
                || path.endsWith(".flac")
                || path.endsWith(".wav")
                || path.endsWith(".ogg")
                || path.endsWith(".opus")
                || path.endsWith(".amr");
    }

    private DataSource.Factory getCacheDataSourceFactory() {
        sCacheEvent = "factory_created";
        if (mCache == null) {
            mCache = newCache();
        }
        // 关键修复:不要设置 FLAG_IGNORE_CACHE_ON_ERROR。ExoPlayer 2.19 的 CacheDataSource
        // 一旦某次写入遇到 IOException(首个 ts 切片网络抖动/超时即可触发),seenCacheError
        // 会被永久置位,之后所有请求都走 shouldIgnoreCacheForRequest() 返回非 -1,
        // 导致整个播放器实例彻底绕过缓存 —— 表现为缓存目录始终 files=1 size=0B。
        // flags 用默认值 0,偶发错误只影响该次请求,不会关闭后续缓存。
        return new CacheDataSource.Factory()
                .setCache(mCache)
                .setUpstreamDataSourceFactory(getDataSourceFactory())
                .setFlags(0)
                .setEventListener(new CacheDataSource.EventListener() {
                    @Override
                    public void onCachedBytesRead(long cacheSizeBytes, long totalBytes) {
                        sCacheEvent = "hit cached=" + cacheSizeBytes + " total=" + totalBytes;
                    }

                    @Override
                    public void onCacheIgnored(int reason) {
                        sCacheEvent = "ignored reason=" + reason + " (0=error,1=unsetLength)";
                    }
                });
    }

    private Cache newCache() {
        return new SimpleCache(
                new File(externalCacheDir(), "exo-video-cache"),//缓存目录
                new LeastRecentlyUsedCacheEvictor(512 * 1024 * 1024),//缓存大小，默认512M，使用LRU算法实现
                new ExoDatabaseProvider(mAppContext));
    }

    private File externalCacheDir()
    {
        File externalCacheDir = mAppContext.getExternalCacheDir();
        if (externalCacheDir == null){
            externalCacheDir = mAppContext.getCacheDir();
        }
        return externalCacheDir;
    }
    /**
     * Returns a new DataSource factory.
     *
     * @return A new DataSource factory.
     */
    private DataSource.Factory getDataSourceFactory() {
        return new com.google.android.exoplayer2.upstream.DefaultDataSourceFactory(mAppContext, getHttpDataSourceFactory());
    }

    /**
     * Returns a new HttpDataSource factory.
     *
     * @return A new HttpDataSource factory.
     */
    private OkHttpDataSource.Factory getHttpDataSourceFactory() {
        if (mHttpDataSourceFactory == null) {
            OkHttpClient client = mClient != null ? mClient : new OkHttpClient.Builder().build();
            mHttpDataSourceFactory = new OkHttpDataSource.Factory(client);
        }
        return mHttpDataSourceFactory;
    }

    private void applyHeaders(Map<String, String> headers) {
        Map<String, String> requestHeaders = new HashMap<>();
        String userAgent = null;
        if (headers != null && headers.size() > 0) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                String key = entry.getKey();
                String value = entry.getValue();
                if (TextUtils.isEmpty(key) || TextUtils.isEmpty(value)) {
                    continue;
                }
                if (HEADER_FORMAT.equalsIgnoreCase(key)) {
                    continue;
                }
                if ("User-Agent".equalsIgnoreCase(key)) {
                    userAgent = value.trim();
                } else {
                    requestHeaders.put(key, value.trim());
                }
            }
        }
        mHttpDataSourceFactory.setUserAgent(userAgent);
        mHttpDataSourceFactory.setDefaultRequestProperties(requestHeaders);
    }

    public void setCache(Cache cache) {
        this.mCache = cache;
    }
}
