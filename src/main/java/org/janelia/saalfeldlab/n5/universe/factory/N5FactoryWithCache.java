package org.janelia.saalfeldlab.n5.universe.factory;

import com.google.gson.JsonElement;
import org.janelia.saalfeldlab.n5.GsonKeyValueN5Reader;
import org.janelia.saalfeldlab.n5.KeyValueRoot;
import org.janelia.saalfeldlab.n5.N5Exception.N5IOException;
import org.janelia.saalfeldlab.n5.N5KeyValueReader;
import org.janelia.saalfeldlab.n5.N5Path.N5DirectoryPath;
import org.janelia.saalfeldlab.n5.N5Reader;
import org.janelia.saalfeldlab.n5.N5Writer;
import org.janelia.saalfeldlab.n5.zarr.ZarrKeyValueReader;
import org.janelia.saalfeldlab.n5.zarr.v3.ZarrV3KeyValueReader;

import java.net.URI;
import java.nio.file.Paths;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * An {@link N5Factory} that caches opened readers and writers by normalized URI.
 * <p>
 * {@code openReader}/{@code openWriter} calls on cached containers are non-blocking and
 * thread-safe. If the container is not cached yet, the open call synchronizes on {@code this}
 * instance to ensure thread safety.
 * <p>
 * The cache is inserted at {@link N5Factory#openReaderHelper} /
 * {@link N5Factory#openWriterHelper}, the single point every {@code openReader}/{@code openWriter}
 * overload funnels through. It is keyed by the {@link KeyValueRoot#uri() root URI}, normalized,
 * and an entry is only handed out if it still matches the requested {@link StorageFormat} and
 * still reads (resp. writes).
 */
public class N5FactoryWithCache extends N5Factory {

	private final ConcurrentHashMap<URI, N5Reader> readerCache = new ConcurrentHashMap<>();
	private final ConcurrentHashMap<URI, N5Writer> writerCache = new ConcurrentHashMap<>();

	/**
	 * Open a reader for the container. If a cached reader is present, return it.
	 * If no cached reader, but a cached writer, return it as an N5Reader.
	 * Finally, open a new reader if not present in the cache.
	 *
	 * @param requiredFormat the storage format, or null
	 * @param root root of the container
	 * @return the N5Reader
	 */
	public N5Reader openReaderAllowCachedWriter(final StorageFormat requiredFormat, final KeyValueRoot root) {

		final N5Reader cachedReader = getCachedReaderAllowWriter(requiredFormat, root.uri());
		if (cachedReader != null)
			return cachedReader;

		return openReader(requiredFormat, root);
	}

	/**
	 * Open a reader for the container. If a cached reader is present, return it.
	 * If no cached reader, but a cached writer, return it as an N5Reader.
	 * Finally, open a new reader if not present in the cache.
	 * <p>
	 * NB: On a cache hit no {@link KeyValueRoot} is created, which is the point of taking the
	 * URI as a {@code String} here — for an {@code s3://} container, building the root means
	 * building a client.
	 *
	 * @param uri the container location, optionally prefixed with a storage format
	 * @return the N5Reader
	 */
	public N5Reader openReaderAllowCachedWriter(final String uri) {

		final N5Location location = N5Location.of(uri);
		final N5Reader cachedReader = getCachedReaderAllowWriter(
				explicitFormat(location.inferredFormat()),
				location.containerUri());
		if (cachedReader != null)
			return cachedReader;

		return openReader(uri);
	}

	private N5Reader getCachedReaderAllowWriter(final StorageFormat requiredFormat, final URI location) {

		/* try to get a cached reader */
		final N5Reader readerFromCache = getReaderFromCache(requiredFormat, location);
		if (readerFromCache != null)
			return readerFromCache;

		/* else see if we have a cached writer we can return as an N5Reader */
		final boolean dontTestWrite = false;
		return getWriterFromCache(requiredFormat, location, dontTestWrite);
	}

	/**
	 * The format a cached reader or writer must have: only an explicit format
	 * counts. A guess from the path suffix may be wrong, and the cache must
	 * not reject a correct entry because of it.
	 */
	private static StorageFormat explicitFormat(final InferredStorageFormat format) {
		return format.isExplicit() ? format.format() : null;
	}

	@Override
	protected N5Reader openReaderHelper(final InferredStorageFormat inferredFormat, final KeyValueRoot root) {

		final StorageFormat requiredFormat = explicitFormat(inferredFormat);

		/* if possible, return cached reader without synchronizing */
		final N5Reader cached = getReaderFromCache(requiredFormat, root.uri());
		if (cached != null)
			return cached;

		synchronized (this) {

			/* another thread may have opened it while we waited */
			final N5Reader reopened = getReaderFromCache(requiredFormat, root.uri());
			if (reopened != null)
				return reopened;

			final N5Reader reader = super.openReaderHelper(inferredFormat, root);
			if (reader != null)
				readerCache.put(normalizeUri(root.uri()), reader);
			return reader;
		}
	}

	/**
	 * Get the cached reader at {@code location}, evicting it if it no longer matches
	 * {@code requiredFormat} or can no longer read.
	 *
	 * @param requiredFormat the foramt the reader must have, or {@code null} to accept any
	 * @param location the container location
	 * @return the cached reader, or {@code null} if there is no valid one
	 */
	protected N5Reader getReaderFromCache(final StorageFormat requiredFormat, final URI location) {

		final URI uri = normalizeUri(location);
		final N5Reader reader = readerCache.get(uri);
		if (reader == null)
			return null;

		final boolean readerIsValid = n5MatchesFormat(reader, requiredFormat) && canRead(reader);
		if (!readerIsValid) {
			readerCache.remove(uri, reader);
			return null;
		}
		return reader;
	}

	@Override
	protected N5Writer openWriterHelper(final InferredStorageFormat inferredFormat, final KeyValueRoot root) {

		final StorageFormat requiredFormat = explicitFormat(inferredFormat);
		final boolean testWrite = true;
		final N5Writer cached = getWriterFromCache(requiredFormat, root.uri(), testWrite);
		if (cached != null)
			return cached;

		synchronized (this) {
			/* see if someone opened this while we were waiting. */
			final N5Writer reopened = getWriterFromCache(requiredFormat, root.uri(), testWrite);
			if (reopened != null)
				return reopened;

			final N5Writer writer = super.openWriterHelper(inferredFormat, root);
			if (writer != null)
				writerCache.put(normalizeUri(root.uri()), writer);
			return writer;
		}
	}

	/**
	 * Get the cached writer at {@code location}, evicting it if it no longer matches
	 * {@code requiredFormat} or, if {@code testWrite}, can no longer write.
	 *
	 * @param requiredFormat the format the writer must have, or {@code null} to accept any
	 * @param location the container location
	 * @param testWrite whether to check that the writer can still write
	 * @return the cached writer, or {@code null} if there is no valid one
	 */
	protected N5Writer getWriterFromCache(final StorageFormat requiredFormat, final URI location, final boolean testWrite) {

		final URI uri = normalizeUri(location);
		final N5Writer writer = writerCache.get(uri);
		if (writer == null)
			return null;

		final boolean writerIsValid = n5MatchesFormat(writer, requiredFormat) && (!testWrite || canWrite(writer));

		if (!writerIsValid) {
			writerCache.remove(uri, writer);
			return null;
		}
		return writer;
	}

	/**
	 * Test if a read attempt on this {@link N5Reader} will succeed.
	 *
	 * @return if read was successful
	 */
	private boolean canRead(final N5Reader reader) {

		try {
			/* NB: Ask the KeyValueRoot, not the reader. The root sits *below* the
			 * HierarchyCache, so it is live by construction, where a cached reader would
			 * answer getAttribute() from its own cache and report a container that has since
			 * vanished as readable — which is exactly what this check is for. It is also
			 * cheaper than reading the root attributes.
			 *
			 * N5DirectoryPath.of("") is how HierarchyCache's own constructor names the root. */
			if (reader instanceof GsonKeyValueN5Reader)
				return ((GsonKeyValueN5Reader)reader).getKeyValueRoot().isDirectory(N5DirectoryPath.of(""));

			/* fallback for readers with no KeyValueRoot, i.e. HDF5 */
			reader.getAttribute("/", "/", JsonElement.class);
			return true;
		} catch (final Exception e) {
			return false;
		}
	}

	/**
	 * Test if a write attempt on this {@link N5Writer} will succeed.
	 * Writes a dummy placeholder metadata key/value that is immediately removed if successful.
	 *
	 * @return if write was successful
	 */
	private boolean canWrite(final N5Writer writer) {

		/* If we already have a writer, and we `canRead`, it means we passed the `canWrite` check the first time.
		 * In this case, that's enough evidence that we `canWrite`. It doesn't guarantee, e.g someone didn't change
		 * permissions underneath us, but we never guarded against that anyway. */
		if (canRead(writer))
			return true;

		synchronized (this) {
			final String uuid = UUID.randomUUID().toString();
			try {
				/* Not ideal, but we want to guarantee that the writer is write-able.
				 * Normally this is guaranteed at construction, but it isn't being constructed here. */
				writer.setAttribute("/", uuid, "CACHE_CHECK");
				return true;
			} catch (final Exception e) {
				return false;
			} finally {
				try {
					/* If it fails, defer to the result of the outer try/catch*/
					writer.removeAttribute("/", uuid);
				} catch (final Exception ignored) {
				}
			}
		}
	}

	@Override
	protected void requireContainerExists(final Supplier<N5Reader> getReader) throws N5IOException {
		N5Reader reader = null;
		try {
			reader = getReader.get();
		} catch (final Exception e) {
			throw new N5IOException("Existing container could not be opened, or does not exist.", e);
		} finally {
			/* For the N5FactoryWithCache, we don't want to close the reader necessarily, since
			 * we may have it cached for later re-use. However, for HDF5 specifically, we must close it
			 * since it is not valid to have a reader and writer open at the same time. */
			if (isHdf5(reader))
				reader.close();
		}
	}

	private boolean n5MatchesFormat(final N5Reader reader, final StorageFormat format) {

		// succeed if no format to compare against
		if (format == null)
			return true;

		switch (format) {
		case N5:
			return reader instanceof N5KeyValueReader && !(reader instanceof ZarrV3KeyValueReader);
		case ZARR2:
			return reader instanceof ZarrKeyValueReader;
		case ZARR3:
			return reader instanceof ZarrV3KeyValueReader;
		case ZARR:
			return reader instanceof ZarrKeyValueReader || reader instanceof ZarrV3KeyValueReader;
		case HDF5:
			return isHdf5(reader);
		default:
			return true; // StorageFormat has no other values, but Java 8 doesn't know that
		}
	}

	/*
	 * Whether a reader *the factory opened* is HDF5. No n5-hdf5 type is named here, since
	 * this package must not depend on n5-hdf5: HDF5 is the one format Containers builds that
	 * is not a key-value container (canRead() relies on the same dichotomy), and only readers
	 * the factory opened ever reach this cache. Revisit if Containers ever builds another
	 * non-key-value format.
	 */
	private static boolean isHdf5(final N5Reader reader) {

		return reader != null && !(reader instanceof GsonKeyValueN5Reader);
	}

	/**
	 * Drop all cached readers and writers.
	 */
	public void clear() {
		readerCache.clear();
		writerCache.clear();
	}

	/**
	 * Drop the cached reader and writer for {@code uri}, if any.
	 *
	 * @return whether anything was removed
	 */
	public boolean remove(final URI uri) {

		final URI normalUri = normalizeUri(uri);
		boolean removed = readerCache.remove(normalUri) != null;
		removed |= writerCache.remove(normalUri) != null;
		return removed;
	}

	/**
	 * Drop the cached reader and writer for {@code uri}, if any.
	 *
	 * @param uri the container location, optionally prefixed with a storage format
	 * @return whether anything was removed
	 */
	public boolean remove(final String uri) {

		return remove(N5Location.of(uri).containerUri());
	}

	private static URI normalizeUri(final URI uri) {

		final URI normalized;
		if (uri.isAbsolute() && !uri.getScheme().equals("file"))
			normalized = uri.normalize();
		else if (uri.isAbsolute())
			normalized = Paths.get(uri).normalize().toUri();
		else
			normalized = Paths.get(uri.getPath()).normalize().toUri();

		/* By default, Path.toUri() adds a trailing `/` only if it is an existing directory.
		 * So a container that doesn't exist yet (e.g. creating a new writer) would get a key
		 * without `/` the first time and one with `/` the next time.
		 *
		 * We add the trailing slash in all cases, to avoid this issue. (Adding rather than
		 * removing it matches KeyValueRoot.uri(), which always ends in `/`, and keeps the
		 * file system root "file:///" a valid URI.) */
		final String str = normalized.toString();
		return str.endsWith("/") ? normalized : URI.create(str + "/");
	}
}
