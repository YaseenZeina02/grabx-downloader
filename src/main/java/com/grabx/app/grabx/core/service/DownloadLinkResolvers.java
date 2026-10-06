package com.grabx.app.grabx.core.service;

import com.grabx.app.grabx.core.model.DownloadRow;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Host plugins resolve authorized links; verification always remains owned by GrabX. */
public final class DownloadLinkResolvers {
    public record Context(String originalUrl, String currentUrl, String referer) { }
    public record Link(String url, String referer) { }
    public interface Resolver {
        boolean supports(String originalUrl);
        Link resolve(Context context) throws Exception;
        default Link refresh(Context context) throws Exception { return resolve(context); }
    }
    private final List<Resolver> resolvers = new CopyOnWriteArrayList<>();
    public void register(Resolver resolver) { resolvers.add(java.util.Objects.requireNonNull(resolver)); }
    public boolean hasResolver(String originalUrl) { return resolvers.stream().anyMatch(r -> r.supports(originalUrl)); }

    void refresh(DownloadRow row, Path output) throws Exception {
        Resolver resolver = resolvers.stream().filter(r -> r.supports(row.originalUrl)).findFirst().orElseThrow();
        Link link = resolver.refresh(new Context(row.originalUrl, row.url, row.getReferer()));
        var verified = DownloadLinkVerifier.verify(link.url(), link.referer(), output, row.resumeTotal, row.resumeSegments, row.requestUserAgent);
        row.url = link.url(); row.setReferer(link.referer());
        row.resumeEtag = verified.etag(); row.resumeTotal = verified.total(); row.resumeSegments = verified.segments();
        row.resumeLastModified = null; row.finalUrl = null; row.resumeSupport = "Yes";
        DirectDownloadState.save(row, output);
    }
}
