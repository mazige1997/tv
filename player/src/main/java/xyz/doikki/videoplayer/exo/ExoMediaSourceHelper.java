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
import com.google.android.exoplayer2.upstream.DataSink;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DataSpec;
import com.google.android.exoplayer2.upstream.cache.Cache;
import com.google.android.exoplayer2.upstream.cache.CacheDataSink;
import com.google.android.exoplayer2.upstream.cache.CacheDataSource;
import com.google.android.exoplayer2.upstream.cache.LeastRecentlyUsedCacheEvictor;
import com.google.android.exoplayer2.upstream.cache.SimpleCache;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import okhttp3.OkHttpClient;

public final class ExoMediaSourceHelper {
    public static final String HEADER_FORMAT = "TVBox-Format";

    private static ExoMediaSourceHelper sInstance;

    private final Context mAppContext;
    private OkHttpDataSource.Factory mHttpDataSourceFactory;
    private Cache mCache;
    private OkHttpClient mClient;
    // 边下边播:由上层(ExoPlayer.kt)根据设置项在每次 setDataSource 前设置,
    // 开启后 http(s) 媒体走 CacheDataSource(本地 SimpleCache,LRU 动态上限),实现边下边播。
    private volatile boolean mCacheEnabled;

    // 缓存上限按设备可用空间动态计算,避免小存储 TV 盒被固定 512MB 占满。
    private static final long MAX_CACHE_BYTES = 512L * 1024 * 1024;
    private static final long MIN_CACHE_BYTES = 64L * 1024 * 1024;
    /** 本次进程实际生效的 LRU 上限,供诊断日志读取。 */
    public static volatile long sCacheLimitBytes = MAX_CACHE_BYTES;

    // 缓存诊断事件(供上层写入诊断日志):记录 CacheDataSource 的缓存命中/忽略原因。
    // reason: 0=CACHE_IGNORED_REASON_ERROR, 1=CACHE_IGNORED_REASON_UNSET_LENGTH。
    public static volatile String sCacheEvent = "none";

    // 决定性诊断:直接统计真正写入本地缓存的字节数。
    // setFlags(0) 后 EventListener 的 onCacheIgnored 不再上报错误、onCachedBytesRead 只在
    // "命中缓存"时触发,首次播放全是从网络读+写缓存,两个回调都不触发,因此 evt 停在
    // factory_created 无法判断是否写入。这里在 CacheDataSink 外层包一层,记录每次 open/
    // write 的真实字节与首个 IOException,snapshot 直接读这些计数即可一锤定音:
    //   writes>0  -> 数据真的落盘了,问题在统计口径;
    //   writes=0 且 err!=none -> 写入抛异常被吞,异常类型即根因;
    //   writes=0 且 opens=0 -> HLS 请求根本没走 CacheDataSource。
    public static final AtomicLong sCacheWriteCalls = new AtomicLong();
    public static final AtomicLong sCacheWriteBytes = new AtomicLong();
    public static final AtomicLong sCacheOpenCalls = new AtomicLong();
    public static volatile String sCacheWriteError = "none";

    // 命中统计:EventListener 的 onCachedBytesRead 只在真正命中本地缓存时触发,
    // onCacheIgnored 记录绕过缓存的次数。与 writes 配合可区分"首播写入"与"重播命中"。
    public static final AtomicLong sCacheHitCalls = new AtomicLong();
    public static final AtomicLong sCacheHitBytes = new AtomicLong();
    public static final AtomicLong sCacheIgnoredCalls = new AtomicLong();

    /**
     * 返回 ExoPlayer 缓存索引记录的已缓存字节数(getCacheSpace),与文件系统遍历互为交叉验证。
     * 未初始化时返回 -1。
     */
    public static long getCacheSpace() {
        try {
            ExoMediaSourceHelper h = sInstance;
            if (h == null || h.mCache == null) return -1L;
            return h.mCache.getCacheSpace();
        } catch (Throwable t) {
            return -2L;
        }
    }

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
        // 每次真正创建缓存工厂时重置写入计数,便于观察"本次播放"是否落盘。
        sCacheWriteCalls.set(0);
        sCacheWriteBytes.set(0);
        sCacheOpenCalls.set(0);
        sCacheWriteError = "none";
        sCacheHitCalls.set(0);
        sCacheHitBytes.set(0);
        sCacheIgnoredCalls.set(0);
        if (mCache == null) {
            mCache = newCache();
        }
        // 显式提供写 DataSink(经诊断包装)。Factory 默认在 upstream!=null 且非只读时
        // 会自动用 CacheDataSink,但那样看不到写入异常。这里包一层记录真实字节数/异常,
        // 让上层日志能直接判断缓存是否在落盘。fragmentSize 用 5MB(ExoPlayer 默认),
        // 对 HLS 分片足够;超过会滚动到新文件,不影响 LRU 总上限。
        final Cache cacheRef = mCache;
        DataSink.Factory writeSinkFactory = new DataSink.Factory() {
            @Override
            public DataSink createDataSink() {
                final DataSink delegate = new CacheDataSink.Factory()
                        .setCache(cacheRef)
                        .setFragmentSize(5 * 1024 * 1024)
                        .createDataSink();
                return new DataSink() {
                    @Override
                    public void open(DataSpec dataSpec) throws IOException {
                        sCacheOpenCalls.incrementAndGet();
                        delegate.open(dataSpec);
                    }

                    @Override
                    public void write(byte[] buffer, int offset, int length) throws IOException {
                        try {
                            delegate.write(buffer, offset, length);
                            sCacheWriteCalls.incrementAndGet();
                            sCacheWriteBytes.addAndGet(length);
                        } catch (IOException e) {
                            if ("none".equals(sCacheWriteError)) {
                                sCacheWriteError = e.getClass().getSimpleName() + ": " + e.getMessage();
                            }
                            throw e;
                        }
                    }

                    @Override
                    public void close() throws IOException {
                        delegate.close();
                    }
                };
            }
        };
        // 关键修复:不要设置 FLAG_IGNORE_CACHE_ON_ERROR。ExoPlayer 2.19 的 CacheDataSource
        // 一旦某次写入遇到 IOException(首个 ts 切片网络抖动/超时即可触发),seenCacheError
        // 会被永久置位,之后所有请求都走 shouldIgnoreCacheForRequest() 返回非 -1,
        // 导致整个播放器实例彻底绕过缓存 —— 表现为缓存目录始终 files=1 size=0B。
        // flags 用默认值 0,偶发错误只影响该次请求,不会关闭后续缓存。
        return new CacheDataSource.Factory()
                .setCache(mCache)
                .setCacheWriteDataSinkFactory(writeSinkFactory)
                .setUpstreamDataSourceFactory(getDataSourceFactory())
                .setFlags(0)
                .setEventListener(new CacheDataSource.EventListener() {
                    @Override
                    public void onCachedBytesRead(long cacheSizeBytes, long totalBytes) {
                        sCacheEvent = "hit cached=" + cacheSizeBytes + " total=" + totalBytes;
                        sCacheHitCalls.incrementAndGet();
                        sCacheHitBytes.addAndGet(cacheSizeBytes);
                    }

                    @Override
                    public void onCacheIgnored(int reason) {
                        sCacheEvent = "ignored reason=" + reason + " (0=error,1=unsetLength)";
                        sCacheIgnoredCalls.incrementAndGet();
                    }
                });
    }

    private Cache newCache() {
        File baseDir = externalCacheDir();
        long limit = computeCacheLimit(baseDir);
        sCacheLimitBytes = limit;
        return new SimpleCache(
                new File(baseDir, "exo-video-cache"),//缓存目录
                new LeastRecentlyUsedCacheEvictor(limit),//按设备可用空间动态计算的 LRU 上限
                new ExoDatabaseProvider(mAppContext));
    }

    /**
     * 按缓存分区可用空间决定 LRU 上限:
     * 目标 = 可用空间的 10%,夹在 [64MB, 512MB];设备存储极小时再压到可用空间的 1/2 以内。
     * 获取失败(usable<=0)时回退旧行为 512MB,保证不因该优化影响播放。
     */
    static long computeCacheLimit(File baseDir) {
        long usable = 0L;
        try {
            usable = baseDir.getUsableSpace();
        } catch (Throwable ignored) {
        }
        if (usable <= 0L) return MAX_CACHE_BYTES;
        long limit = Math.max(MIN_CACHE_BYTES, Math.min(MAX_CACHE_BYTES, usable / 10L));
        long half = usable / 2L;
        if (limit > half) limit = half;
        return Math.max(1L, limit);
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
