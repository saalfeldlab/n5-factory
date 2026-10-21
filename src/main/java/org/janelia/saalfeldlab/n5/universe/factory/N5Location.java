package org.janelia.saalfeldlab.n5.universe.factory;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.janelia.saalfeldlab.n5.KeyValueRoot;
import org.janelia.saalfeldlab.n5.N5Exception;
import org.janelia.saalfeldlab.n5.N5Path.N5DirectoryPath;

/**
 * A parsed N5 location: where a container is, which format it is in (and whether that is explicit, a guess,
 * or unknown), and optionally a group and attribute within it.
 * <p>
 * A location has three parts:
 * <ul>
 * <li>{@link #containerUri()}: the container root, as an absolute, normalized URI without
 * storage-scheme prefix, query, or fragment. This is what a {@code KeyValueRoot} is created from.</li>
 * <li>{@link #inferredFormat()}: the format named by a storage-scheme prefix ({@code "zarr3://"},
 * {@code "n5:"}, …), else guessed from the path suffix ({@code ".zarr"}, {@code ".n5"}, {@code ".h5"}),
 * else unknown. ({@link InferredStorageFormat#resolveFromKeys} may be used to confirm or revise a guess,
 * but this is not the job of N5Location.)</li>
 * <li>The N5 group ({@code "?group"}) and attribute ({@code "#attribute"}), carried in the query and
 * fragment of {@link #uri()}, and decoded in {@link #groupPath()} and {@link #attributePath()}.</li>
 * </ul>
 * {@link #uri()} puts the parts back together into a single URI, the canonical form of this location.
 * (The canonical form is parsed by {@link #of(URI)} into exactly the same location).
 * <p>
 * Use {@link #of(String)} for locations typed by a user. It accepts URIs and local paths, encoded or
 * not, and turns them into canonical form. Some examples, with working directory {@code /home/me}:
 * <table>
 * <caption>Examples of {@code N5Location.of(String)}</caption>
 * <tr><th>input</th><th>{@link #uri()}</th><th>{@link #inferredFormat()}</th></tr>
 * <tr><td>{@code "/data/x.n5"}</td><td>{@code file:///data/x.n5}</td><td>N5, from suffix</td></tr>
 * <tr><td>{@code "data/x.n5"}</td><td>{@code file:///home/me/data/x.n5}</td><td>N5, from suffix</td></tr>
 * <tr><td>{@code "/data/my x"}</td><td>{@code file:///data/my%20x}</td><td>unknown</td></tr>
 * <tr><td>{@code "my%20x"}</td><td>{@code file:///home/me/my%2520x}</td><td>unknown</td></tr>
 * <tr><td>{@code "/data/./a/../x"}</td><td>{@code file:///data/x}</td><td>unknown</td></tr>
 * <tr><td>{@code "file://localhost/data/x"}</td><td>{@code file:///data/x}</td><td>unknown</td></tr>
 * <tr><td>{@code "/data/x.zarr?group/a#attr"}</td><td>{@code file:///data/x.zarr?group/a#attr}</td><td>ZARR, from suffix</td></tr>
 * <tr><td>{@code "s3://bucket/my x"}</td><td>{@code s3://bucket/my%20x}</td><td>unknown</td></tr>
 * <tr><td>{@code "zarr3://s3://bucket/x"}</td><td>{@code zarr3://s3://bucket/x}</td><td>ZARR3, explicit</td></tr>
 * <tr><td>{@code "h5:/data/x.h5"}</td><td>{@code hdf5://file:///data/x.h5}</td><td>HDF5, explicit</td></tr>
 * <tr><td>{@code "n5://data/x"}</td><td>{@code n5://file:///home/me/data/x}</td><td>N5, explicit</td></tr>
 * </table>
 * Note that a local path is taken literally ({@code "my%20x"} is a file with a {@code %} in its
 * name), and that the {@code "//"} after a storage-scheme prefix is not part of the path
 * ({@code "n5://data/x"} is relative). Blank input, opaque URIs ({@code "mailto:x"}), and input
 * containing a line break are rejected.
 * <p>
 * {@link #of(URI)} takes a {@code URI}, which is already encoded. It parses what {@link #uri()}
 * returns, and otherwise differs from {@code of(String)} in that "%" escapes are decoded:
 * {@code of(URI.create("my%20x"))} is the file {@code "my x"}, at {@code file:///home/me/my%20x},
 * where {@code of("my%20x")} is the file {@code "my%20x"}.
 * <p>
 * {@link #ofContainer(URI)} is for a container that is already known by its URI (for a local
 * container, {@link Path#toUri()}, so {@code "?"} and {@code "#"} in a file name are just
 * characters). The container URI must not have a query, fragment, or storage-scheme prefix.
 * <p>
 * {@link #withFormat}, {@link #withGroup}, and {@link #withAttribute} replace one of the optional
 * parts and keep the others. For example, {@code
 * N5Location.ofContainer(uri).withFormat(StorageFormat.ZARR3).withGroup("a/b")}.
 */
public final class N5Location {

	/**
	 * URI of the container root: absolute, normalized,
	 * without storage scheme prefix, query, or fragment.
	 */
	private final URI containerUri;

	/**
	 * The canonical URI of this location: absolute, normalized,
	 * with scheme prefix if the format is explicit,
	 * with query and fragment if group and attribute are non-null.
	 */
	private final URI uri;

	/**
	 * What is known about the container format:
	 * Explicitly named by a storage-scheme prefix, guessed from a container path suffix, or unknown.
	 */
	private final InferredStorageFormat inferredFormat;

	/**
	 * The decoded and normalized query of {@link #uri}, or {@code null}.
	 */
	private final N5DirectoryPath groupPath;

	/**
	 * The decoded fragment of {@link #uri}, or {@code null}.
	 */
	private final String attributePath;

	/**
	 * Assemble a location from its parts. {@code rawQuery} and {@code rawFragment} are encoded, or {@code null}.
	 *
	 * @param explicitFormat
	 * 		explicit format, may be {@code null}. If present, will be prepended to {@code containerUri} to form {@link #uri()}.
	 * @param containerUri
	 * 		absolute URI of the container root (without scheme prefix, query, or fragment).
	 * @param rawQuery
	 * 		query part (group path) of the location. Encoded, that is with illegal URI characters escaped.
	 * 		If present, will be appended with "?" to {@code containerUri} to form {@link #uri()}.
	 * @param rawFragment
	 * 		fragment part (attribute path) of the location. Encoded, that is with illegal URI characters escaped.
	 * 		If present, will be appended with "#" to {@code containerUri} to form {@link #uri()}.
	 */
	N5Location(
			final StorageFormat explicitFormat,
			final URI containerUri,
			final String rawQuery,
			final String rawFragment) {

		this.containerUri = containerUri;

		final String fmt = explicitFormat == null ? "" : explicitFormat.scheme() + "://";
		final String query = rawQuery == null ? "" : "?" + rawQuery;
		final String fragment = rawFragment == null ? "" : "#" + rawFragment;
		this.uri = URI.create(fmt + containerUri + query + fragment);

		this.inferredFormat = InferredStorageFormat.of(explicitFormat, containerUri);
		this.groupPath = rawQuery == null ? null : N5DirectoryPath.of(this.uri.getQuery());
		// NB: In Java 8, URI did not decode "%" escapes inside "[...]", which
		// is why N5URI has its own decodeFragment(). Since Java 9, URI behaves
		// correctly, and we don't need decodeFragment anymore.
		this.attributePath = rawFragment == null ? null : this.uri.getFragment();
	}

	/**
	 * Get the URI of the container root: absolute, normalized, without storage
	 * scheme prefix, query, or fragment.
	 * <p>
	 * To open a {@code N5Reader}, pass this to {@link N5Factory#openReader(StorageFormat, URI)},
	 * together with the inferred {@code StorageFormat} if it was explicit.
	 *
	 * @return the container root URI
	 */
	public URI containerUri() {
		return containerUri;
	}

	/**
	 * Get the canonical URI of this location.
	 * <p>
	 * This is {@link #containerUri()} (absolute, normalized), with the storage scheme prefix
	 * if the {@link #inferredFormat() format} is explicit, and with query and fragment if
	 * group and attribute are specified.
	 *
	 * @return the canonical location URI
	 */
	public URI uri() {
		return uri;
	}

	/**
	 * Get the inferred format of this location:
	 * The {@link StorageFormat} and how it was determined:
	 * explicitly named by a storage-scheme prefix, guessed from a container path suffix, or unknown.
	 *
	 * @return the inferred storage format
	 */
	public InferredStorageFormat inferredFormat() {
		return inferredFormat;
	}

	/**
	 * Get the {@link StorageFormat} of this location if it is explicitly specified,
	 * or {@code null} otherwise.
	 *
	 * @return the explicitly specified storage format, or {@code null}
	 */
	public StorageFormat explicitFormat() {
		return inferredFormat.isExplicit() ? inferredFormat.format() : null;
	}

	/**
	 * The N5 group of this location: the query of {@link #uri()}, decoded and
	 * normalized, or {@code null} if there is none. For example, {@code
	 * "/data/x.zarr?/a//b/"} has group path {@code "a/b"}, and {@code
	 * "/data/x.zarr?"} has the root group {@code ""}.
	 * <p>
	 * Decoding follows the input: {@code of("/data/x.zarr?my%20b")} has group
	 * path {@code "my%20b"} (a local path is taken literally), whereas {@code
	 * of(URI.create("file:///data/x.zarr?my%20b"))} has {@code "my b"}.
	 * <p>
	 * A missing group (URI without a query) is {@code null}. Use {@link
	 * #groupPathOrRoot()} when a missing group should be returned as
	 * the root group {@code ""} instead.
	 *
	 * @return the group path, or {@code null}
	 */
	public N5DirectoryPath groupPath() {
		return groupPath;
	}

	private static final N5DirectoryPath ROOT_GROUP = N5DirectoryPath.of("");

	/**
	 * The N5 group of this location, or the root group {@code ""} if
	 * there is no group (URI without a query). Use {@link #groupPath()},
	 * if you need to distinguish this case explicitly.
	 *
	 * @return the group path, or the root group
	 */
	public N5DirectoryPath groupPathOrRoot() {
		return groupPath == null ? ROOT_GROUP : groupPath;
	}

	/**
	 * The N5 attribute path of this location: the fragment of {@link #uri()},
	 * decoded, or {@code null} if there is none. Escapes inside {@code "[...]"}
	 * are decoded, too.
	 * <p>
	 * The path is not normalized (n5 {@code getAttribute} etc. normalize
	 * attribute paths themselves). A missing attribute path (URI without a
	 * fragment) is {@code null}. Use {@link #attributePathOrRoot()}
	 * when a missing attribute path should be returned as the root
	 * {@code "/"} instead.
	 *
	 * @return the attribute path, or {@code null}
	 */
	public String attributePath() {
		return attributePath;
	}

	/**
	 * The N5 attribute path of this location, or the root {@code "/"} if there is
	 * no attribute path (URI without a fragment). Use {@link #attributePath()},
	 * if you need to distinguish this case explicitly.
	 *
	 * @return the attribute path, or {@code "/"}
	 */
	public String attributePathOrRoot() {
		return attributePath == null ? "/" : attributePath;
	}

	/**
	 * This location with explicit format {@code format}, replacing the {@link #inferredFormat()}
	 * of this location. Container, group, and attribute path are kept.
	 * <p>
	 * For example, {@code N5Location.of("s3://b/x?g").withFormat(StorageFormat.ZARR3)}
	 * is {@code "zarr3://s3://b/x?g"}.
	 *
	 * @param format
	 * 		the explicit format, or {@code null} for a location without explicit format
	 *
	 * @return the location with {@code format}
	 */
	public N5Location withFormat(final StorageFormat format) {

		return new N5Location(format, containerUri, uri.getRawQuery(), uri.getRawFragment());
	}

	/**
	 * This location with N5 group {@code group}, replacing the group of this
	 * location (if any). Container, storage-scheme prefix, and attribute path
	 * are kept. For example, {@code N5Location.of("zarr3://s3://b/x#t")
	 * .withGroup(N5DirectoryPath.of("my a"))} is {@code
	 * "zarr3://s3://b/x?my%20a#t"}.
	 * <p>
	 * The group is encoded for the query of {@link #uri()}, so that {@link
	 * #groupPath()} returns {@code group} again (normalized), whatever
	 * characters it contains.
	 *
	 * @param group
	 * 		the group, or {@code null} for a location without group
	 *
	 * @return the location with {@code group}
	 */
	public N5Location withGroup(final N5DirectoryPath group) {

		final String rawQuery = group == null ? null : encode(group.normalPath(), null).getRawQuery();
		return new N5Location(explicitFormat(), containerUri, rawQuery, uri.getRawFragment());
	}

	/**
	 * This location with N5 group {@code group}, replacing the group of this
	 * location (if any). Same as {@link #withGroup(N5DirectoryPath)
	 * withGroup(N5DirectoryPath.of(group))}.
	 * <p>
	 * NB: {@code group} is a group path, not a query: it is not decoded, so
	 * {@code withGroup("my%20a")} means the literal group {@code "my%20a"}.
	 *
	 * @param group
	 * 		the group path, or {@code null} for a location without group
	 *
	 * @return the location with {@code group}
	 */
	public N5Location withGroup(final String group) {

		return withGroup(group == null ? null : N5DirectoryPath.of(group));
	}

	/**
	 * This location with N5 attribute path {@code attribute}, replacing the
	 * attribute path of this location (if any). Container, storage-scheme
	 * prefix, and group are kept.
	 * <p>
	 * The attribute path is encoded for the fragment of {@link #uri()}, so
	 * that {@link #attributePath()} returns {@code attribute} again. Like
	 * {@link #attributePath()}, it is not normalized, and it is not decoded:
	 * {@code withAttribute("my%20a")} means the literal attribute path
	 * {@code "my%20a"}.
	 *
	 * @param attribute
	 * 		the attribute path, or {@code null} for a location without attribute path
	 *
	 * @return the location with {@code attribute}
	 */
	public N5Location withAttribute(final String attribute) {

		final String rawFragment = attribute == null ? null : encode(null, attribute).getRawFragment();
		return new N5Location(explicitFormat(), containerUri, uri.getRawQuery(), rawFragment);
	}

	/**
	 * Returns {@link #uri()} as a string.
	 */
	@Override
	public String toString() {
		return uri.toString();
	}

	@Override
	public final boolean equals(final Object o) {
		if (!(o instanceof N5Location))
			return false;

		final N5Location that = (N5Location) o;
		return uri.equals(that.uri);
	}

	@Override
	public int hashCode() {
		return uri.hashCode();
	}

	// ------------------------------------------------------------------------
	//
	// -- factory methods --
	//

	/**
	 * The location of a container at {@code containerUri},
	 * for example the {@link KeyValueRoot#uri() uri()} of a {@code KeyValueRoot},
	 * or the {@link Path#toUri()} of a local container.
	 * <p>
	 * The format is guessed from the path suffix (labeled as a {@link InferredStorageFormat.Source#SUFFIX guess}), if possible.
	 * <p>
	 * The given {@code containerUri} must be an absolute, hierarchical URI without storage-scheme prefix, query, or fragment.
	 * The returned location has no explicit format, no group, and no attribute; use {@link #withFormat}, {@link #withGroup},
	 * and {@link #withAttribute} to add them.
	 *
	 * @param containerUri
	 * 		an absolute, hierarchical URI without storage-scheme prefix, query, or fragment
	 *
	 * @return the location
	 *
	 * @throws N5Exception
	 * 		if {@code containerUri} is not of the required form
	 */
	public static N5Location ofContainer(final URI containerUri) throws N5Exception {

		if (containerUri.getRawQuery() != null || containerUri.getRawFragment() != null)
			throw new N5Exception("Container URI has a query or fragment: \"" + containerUri + "\"");
		if (StorageFormat.fromScheme(containerUri.getScheme()) != null)
			throw new N5Exception("Container URI has a storage-scheme prefix: \"" + containerUri + "\"");

		try {
			return new N5Location(null, canonicalContainerUri(containerUri), null, null);
		} catch (final IllegalArgumentException e) {
			throw new N5Exception("Could not parse location: \"" + containerUri + "\"", e);
		}
	}

	/**
	 * Parse an N5 URI: an optional storage-scheme prefix, then a hierarchical
	 * URI, optionally with an N5 group ({@code "?group"}) and attribute
	 * ({@code "#attribute"}). This is the form {@link #uri()} returns, so
	 * {@code of(location.uri())} reproduces {@code location}.
	 * <p>
	 * A relative URI is a local path, resolved against the working directory.
	 * Like any URI, it is encoded: {@code URI.create("my%20x")} names a file
	 * {@code "my x"} (whereas {@link #of(String) of("my%20x")} names a file
	 * {@code "my%20x"}). A network-path reference ({@code "//server/share/x"})
	 * is passed to the platform as is: {@code /server/share/x} on Unix, the UNC
	 * path {@code \\server\share\x} on Windows. A drive letter after a
	 * leading slash ({@code "/C:/x"}) is the drive {@code C:} on Windows, which is
	 * also what resolving it against {@code "file:///"} gives. Opaque URIs
	 * ({@code "mailto:x"}) are rejected.
	 *
	 * @param uri
	 * 		the N5 URI
	 *
	 * @return the location
	 *
	 * @throws N5Exception
	 * 		if {@code uri} is empty, opaque, or cannot be parsed
	 */
	public static N5Location of(final URI uri) throws N5Exception {

		return N5LocationParser.parse(uri);
	}

	/**
	 * Parse a location from user input.
	 * <ol>
	 * <li>Leading and trailing whitespace is trimmed.</li>
	 * <li>An optional storage-scheme prefix ({@code "zarr3://"}, {@code "n5:"},
	 * …) is split off.</li>
	 * <li>If the rest starts with a URI scheme (at least two characters, then
	 * {@code ":"}), it is a URI. It is parsed as is if it is well-formed, and
	 * otherwise taken to be unencoded and encoded, so {@code "s3://b/my x"}
	 * works. It must be hierarchical: opaque URIs ({@code "mailto:x"}) are
	 * rejected.</li>
	 * <li>Otherwise the rest is a local path. It is taken literally: nothing
	 * is percent-decoded, so {@code "my%20x"} names a file with a {@code %}
	 * in its name (whereas {@link #of(URI) of(URI.create("my%20x"))} names a
	 * file {@code "my x"}). The path is resolved by the platform ({@link Paths#get}),
	 * so relative paths are resolved against the working directory, and Windows
	 * drive letters ({@code "C:\data"}) and UNC paths ({@code "\\server\share"})
	 * work. So does a drive letter after a leading slash ({@code "/C:/data"}, as
	 * returned by {@link URI#getPath()} for a {@code file:} URI on Windows).</li>
	 * </ol>
	 * Both URI and local path may specify an N5 group as query({@code "?group"}) and
	 * attribute as fragment ({@code "#attribute"}).
	 * <p>
	 * For local paths the fragment starts at the last {@code "#"}, the query at the first
	 * {@code "?"} before it. A local path's query/fragment are never decoded:
	 * {@code "/x?a%20b"} is group {@code "a%20b"}.
	 * <p>
	 * Edge case: A malformed URI is taken as unencoded *as a whole*, so its query and
	 * fragment are not decoded either: in {@code "s3://b/my x?a%20b"} the space makes
	 * it malformed, so the group is {@code "a%20b"}.
	 *
	 * @param input
	 * 		the location to parse
	 *
	 * @return the parsed location
	 *
	 * @throws N5Exception
	 * 		if {@code input} is blank, an opaque URI, or cannot be parsed
	 */
	public static N5Location of(final String input) throws N5Exception {

		return N5LocationParser.parse(input);
	}

	// ------------------------------------------------------------------------
	//
	// -- private helpers --
	//

	/**
	 * The canonical, normalized form of an absolute, hierarchical {@code containerUri}.
	 * <p>
	 * The canonical form for a local file is the one {@code Path.normalize().toUri()} produces:
	 * <ul>
	 * <li>{@code "file:///"} with an empty authority ({@code "file:/x"} and {@code "file://localhost/x"}
	 * both become {@code "file:///x"}),</li>
	 * <li>{@code "."} and {@code ".."} segments removed,</li>
	 * <li>characters percent-encoded the way the platform encodes them, and</li>
	 * <li>on Windows the drive letter as the first path segment ({@code "file:///C:/x"}).</li>
	 * </ul>
	 * NB: This is the form a local path given without a scheme ends up in, so a file named by path
	 * and the same file named by {@code file:} URI have equal container URIs.
	 *
	 * @param containerUri
	 * 		URI of a container root, must be absolute and hierarchical,
	 * 		must not have a scheme prefix, query, or fragment).
	 *
	 * @return normalized container URI
	 */
	static URI canonicalContainerUri(final URI containerUri) {

		if (!containerUri.isAbsolute())
			throw new N5Exception("Not an absolute URI: \"" + containerUri + "\"");
		if (containerUri.isOpaque())
			throw new N5Exception("Opaque URIs are not supported: \"" + containerUri + "\"");

		final boolean isFile = "file".equalsIgnoreCase(containerUri.getScheme());
		// NB: "file://localhost/x" is the same file as "file:///x".
		final String authority = isFile && "localhost".equalsIgnoreCase(containerUri.getRawAuthority())
				? null
				: containerUri.getRawAuthority();

		// NB: strip query and fragment, but keep authority if present.
		final String path = authority == null
				? containerUri.getRawPath()
				: "//" + authority + containerUri.getRawPath();
		final URI pathUri = URI.create(containerUri.getScheme() + ":" + path);

		if (isFile && authority == null) {
			return Paths.get(pathUri).normalize().toUri();
		} else {
			return pathUri.normalize();
		}

		// NB: Path.toUri() stats the file and appends  "/" if it is an existing directory,
		// so the canonical form  depends on the filesystem at the time of the call:
		// "/data/x.n5" gives a different containerUri before and after the container
		// is created. The only place it matters is cache keys in CachingN5Factory and that
		// applies its own normalization to the container URI. So we just live with it ...
	}

	/**
	 * The relative URI {@code "?query#fragment"} of the unencoded {@code query}
	 * and {@code fragment} (each {@code null} if absent). The multi-argument URI
	 * constructor quotes every character that is illegal in a query or fragment,
	 * and always quotes {@code "%"}, so nothing is decoded later that was not
	 * encoded here.
	 */
	private static URI encode(final String query, final String fragment) {

		try {
			return new URI(null, null, null, query, fragment);
		} catch (final URISyntaxException e) {
			// NB: Cannot happen: without scheme, authority, and path, nothing is parsed.
			throw new IllegalArgumentException(e);
		}
	}
}
