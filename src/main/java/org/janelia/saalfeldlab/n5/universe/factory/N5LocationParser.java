package org.janelia.saalfeldlab.n5.universe.factory;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.janelia.saalfeldlab.n5.N5Exception;

import static org.janelia.saalfeldlab.n5.universe.factory.N5Location.canonicalContainerUri;

class N5LocationParser {

	/**
	 * Parse an N5 URI: an optional storage-scheme prefix, then a hierarchical
	 * URI, optionally with an N5 group ({@code "?group"}) and attribute
	 * ({@code "#attribute"}). This is the form {@link N5Location#uri()} returns,
	 * so {@code N5Location.of(location.uri())} reproduces {@code location}.
	 * <p>
	 * A relative URI is a local path, resolved against the working directory.
	 * Like any URI, it is encoded: {@code URI.create("my%20x")} names a file
	 * {@code "my x"} (whereas {@link #parse(String) of("my%20x")} names a file
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
	static N5Location parse(final URI uri) throws N5Exception {

		final PrefixSplit split = splitPrefix(uri.toString());
		if (split.rest.isEmpty())
			throw new N5Exception("Empty location: \"" + uri + "\"");

		try {
			final URI rest = new URI(split.rest);
			final URI absolute = rest.isAbsolute() ? rest : resolveRelative(rest);
			return new N5Location(split.format, canonicalContainerUri(absolute), absolute.getRawQuery(), absolute.getRawFragment());
		} catch (final URISyntaxException | IllegalArgumentException e) {
			// NB: IllegalArgumentException includes InvalidPathException from Paths.get
			throw new N5Exception("Could not parse location: \"" + uri + "\"", e);
		}
	}

	/**
	 * Parse a location from user input.
	 * <ol>
	 * <li>Leading and trailing whitespace is trimmed.</li>
	 * <li>An optional storage-scheme prefix ({@code "zarr3://"}, {@code "n5:"},
	 * …) is split off, see {@link #splitPrefix}.</li>
	 * <li>If the rest starts with a URI scheme (at least two characters, then
	 * {@code ":"}), it is a URI. It is parsed as is if it is well-formed, and
	 * otherwise taken to be unencoded and encoded, so {@code "s3://b/my x"}
	 * works. It must be hierarchical: opaque URIs ({@code "mailto:x"}) are
	 * rejected.</li>
	 * <li>Otherwise the rest is a local path, possibly with an N5 group
	 * ({@code "?group"}) and attribute ({@code "#attribute"}). It is taken
	 * literally: nothing is percent-decoded, so {@code "my%20x"} names a file
	 * with a {@code %} in its name (whereas {@link #parse(URI)
	 * of(URI.create("my%20x"))} names a file {@code "my x"}). The fragment starts
	 * at the last {@code "#"}, the query at the first {@code "?"} before it.
	 * The path is resolved by the platform ({@link Paths#get}), so relative paths
	 * are resolved against the working directory, and Windows drive letters
	 * ({@code "C:\data"}) and UNC paths ({@code "\\server\share"}) work. So does a
	 * drive letter after a leading slash ({@code "/C:/data"}, as returned by
	 * {@link URI#getPath()} for a {@code file:} URI on Windows).</li>
	 * </ol>
	 *
	 * @param input
	 * 		the location to parse
	 *
	 * @return the parsed location
	 *
	 * @throws N5Exception
	 * 		if {@code input} is blank, an opaque URI, or cannot be parsed
	 */
	static N5Location parse(final String input) throws N5Exception {

		final PrefixSplit split = splitPrefix(input.trim());
		final String rest = split.rest;
		if (rest.isEmpty())
			throw new N5Exception("Empty location: \"" + input + "\"");

		try {
			final URI uri = looksLikeAbsoluteUri(rest)
					? parseUri(rest)
					// This seems to be a local path, assumed un-encoded
					// ("my%20x" is a file called "my%20x", not "my x").
					: parsePath(rest);
			return new N5Location(split.format, canonicalContainerUri(uri), uri.getRawQuery(), uri.getRawFragment());
		} catch (final URISyntaxException | IllegalArgumentException e) {
			// NB: IllegalArgumentException includes InvalidPathException from Paths.get
			throw new N5Exception("Could not parse location: \"" + input + "\"", e);
		}
	}

	// ------------------------------------------------------------------------
	//
	// -- private helpers --
	//

	/**
	 * The result of {@link #splitPrefix}: the format named by a storage-scheme
	 * prefix, and the rest of the input after it.
	 */
	private static final class PrefixSplit {

		/**
		 * The format named by the prefix, or {@code null} if there was no prefix.
		 */
		final StorageFormat format;

		/**
		 * The input after the prefix (and its {@code ":"} or {@code "://"}), or
		 * the whole input if there was no prefix. Not parsed in any way.
		 */
		final String rest;

		private PrefixSplit(final StorageFormat format, final String rest) {
			this.format = format;
			this.rest = rest;
		}
	}

	private static final String SCHEME_GROUP = "scheme";
	private static final String REST_GROUP = "rest";

	/*
	 * Splits "zarr3://some/path" into scheme token and rest. The scheme
	 * alternation is built from StorageFormat.schemePattern(), so the accepted
	 * tokens have a single source of truth.
	 */
	private static final Pattern PREFIX_AND_REST = Pattern.compile(
			"^(?:\\s*(?<" + SCHEME_GROUP + ">"
					+ Arrays.stream(StorageFormat.values())
					.map(format -> format.schemePattern().pattern())
					.collect(Collectors.joining("|"))
					+ "):(?://)?)?(?<" + REST_GROUP + ">.*)$",
			Pattern.CASE_INSENSITIVE);

	/**
	 * Split an optional storage-scheme prefix off {@code input}. For example:
	 * <ul>
	 * <li>{@code "zarr3://s3://bucket/x"} becomes {@code (ZARR3, "s3://bucket/x")}</li>
	 * <li>{@code "h5:/data/x.h5"} becomes {@code (HDF5, "/data/x.h5")}</li>
	 * <li>{@code "s3://bucket/x"} becomes {@code (null, "s3://bucket/x")}</li>
	 * </ul>
	 * This only looks at the characters. Nothing is parsed as a URI. Leading
	 * whitespace before a prefix is skipped.
	 * <p>
	 * NB: The {@code "//"} after the prefix is consumed, so {@code "n5://data/x"}
	 * has the <em>relative</em> rest {@code "data/x"}. Use {@code "n5:///data/x"}
	 * for an absolute path.
	 *
	 * @param input
	 * 		a location, optionally prefixed with a storage scheme
	 *
	 * @return the prefix format (or {@code null}) and the rest of {@code input}
	 *
	 * @throws N5Exception
	 * 		if {@code input} contains a line terminator, the only input that does
	 * 		not split
	 */
	private static PrefixSplit splitPrefix(final String input) {

		final Matcher matcher = PREFIX_AND_REST.matcher(input);
		if (!matcher.matches())
			// NB: We end up here, if the input String contains a line
			// separator. (Without DOTALL, "." does not match line terminators).
			// An interior line break in a location is almost certainly a paste
			// accident, so reject it.
			throw new N5Exception("Could not parse URI: \"" + input + "\"");

		final String token = matcher.group(SCHEME_GROUP);
		final StorageFormat format = StorageFormat.fromScheme(token);
		return new PrefixSplit(format, matcher.group(REST_GROUP));
	}


	/**
	 * A URI scheme of at least two characters, followed by ":".
	 * A single letter followed by ":" is guessed to be a Windows drive
	 * ("C:/data", "C:\data"), not a scheme.
	 */
	private static final Pattern URI_SCHEME = Pattern.compile("^[A-Za-z][A-Za-z0-9+.-]+:");

	/**
	 * Whether {@code input} starts with a URI scheme, that is, at least two
	 * characters followed by {@code ":"}. A single letter followed by {@code
	 * ":"} looks like a Windows drive ({@code "C:/data"}), not a scheme.
	 */
	private static boolean looksLikeAbsoluteUri(final String input) {
		return URI_SCHEME.matcher(input).find();
	}

	/**
	 * Parse a local path, unencoded, possibly with query and fragment: the
	 * fragment starts at the last {@code "#"}, the query at the first {@code
	 * "?"} before it. The path is resolved by the platform ({@link Paths#get}),
	 * the result is an absolute {@code file:} URI (not normalized).
	 */
	private static URI parsePath(final String input) throws URISyntaxException {

		final int fragmentIdx = input.lastIndexOf('#');
		final String withoutFragment = fragmentIdx < 0 ? input : input.substring(0, fragmentIdx);
		final String fragment = fragmentIdx < 0 ? null : input.substring(fragmentIdx + 1);
		final int queryIdx = withoutFragment.indexOf('?');
		final String path = queryIdx < 0 ? withoutFragment : withoutFragment.substring(0, queryIdx);
		final String query = queryIdx < 0 ? null : withoutFragment.substring(queryIdx + 1);

		// NB: "//a/b" is passed to the platform as is: /a/b on Unix, the UNC path \\a\b on Windows.
		final URI pathUri = localPath(path).toUri();

		// NB: Concatenated, because pathUri is already encoded, and passing its path to the
		// multi-argument constructor would quote the "%" again.
		return URI.create(pathUri.toString() + encode(query, fragment));
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

	/**
	 * A drive letter after a leading slash, followed by a separator: {@code "/C:/"}.
	 */
	private static final Pattern SLASH_DRIVE = Pattern.compile("^/[A-Za-z]:[/\\\\]");

	/**
	 * {@link Paths#get} of the unencoded local {@code path}, also accepting a
	 * drive letter after a leading slash ({@code "/C:/x"}, the form {@link
	 * URI#getPath()} returns for a {@code file:} URI on Windows).
	 * <p>
	 * NB: The slash is stripped only if {@code Paths.get} rejects the path as
	 * it is. So this never changes the meaning of a path that is valid on the
	 * platform, and only applies on Windows: on Unix, {@code "/C:/x"} is the
	 * directory {@code "C:"} below the root. {@code "/C:"} without a separator
	 * is not accepted, because {@code "C:"} would be relative to the current
	 * directory of drive {@code C:}, not its root.
	 */
	private static Path localPath(final String path) {

		try {
			return Paths.get(path);
		} catch (final InvalidPathException e) {
			if (SLASH_DRIVE.matcher(path).find())
				return Paths.get(path.substring(1));
			throw e;
		}
	}

	/**
	 * Resolve a relative URI as a local path. Unlike {@link #parsePath}, the
	 * input is encoded: the path is decoded, query and fragment are kept as
	 * they are. The path is resolved by the platform ({@link Paths#get}), the
	 * result is an absolute {@code file:} URI (not normalized).
	 */
	private static URI resolveRelative(final URI uri) {

		// NB: A network-path reference keeps its authority, and "//server/x" is passed
		// to the platform as is, as in parsePath(): /server/x on Unix, the UNC path
		// \\server\x on Windows.
		final String authority = uri.getAuthority();
		final String path = authority == null ? uri.getPath() : "//" + authority + uri.getPath();
		final URI pathUri = localPath(path).toUri();

		final String query = uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery();
		final String fragment = uri.getRawFragment() == null ? "" : "#" + uri.getRawFragment();
		return URI.create(pathUri + query + fragment);
	}

	/**
	 * Parse {@code uriString}, which starts with a URI scheme. If it is a
	 * well-formed URI, it is taken as is.
	 * <p>
	 * Otherwise, it is assumed to be unencoded: it is split into components
	 * (fragment at the last {@code "#"}, query at the first {@code "?"}
	 * before it, authority after a leading {@code "//"} up to the next
	 * {@code "/"}), and everything that is not legal in a component is
	 * quoted.
	 */
	private static URI parseUri(final String uriString) throws URISyntaxException {

		try {
			return new URI(uriString);
		} catch (final URISyntaxException ignored) {}

		final int colonIdx = uriString.indexOf(':');
		final String scheme = uriString.substring(0, colonIdx);
		String rest = uriString.substring(colonIdx + 1);

		final int fragmentIdx = rest.lastIndexOf('#');
		final String fragment = fragmentIdx < 0 ? null : rest.substring(fragmentIdx + 1);
		rest = fragmentIdx < 0 ? rest : rest.substring(0, fragmentIdx);

		final int queryIdx = rest.indexOf('?');
		final String query = queryIdx < 0 ? null : rest.substring(queryIdx + 1);
		rest = queryIdx < 0 ? rest : rest.substring(0, queryIdx);

		final String authority;
		final String path;
		if (rest.startsWith("//")) {
			final int pathIdx = rest.indexOf('/', 2);
			authority = pathIdx < 0 ? rest.substring(2) : rest.substring(2, pathIdx);
			path = pathIdx < 0 ? "" : rest.substring(pathIdx);
		} else {
			authority = null;
			path = rest;
		}
		return new URI(scheme, authority, path, query, fragment);
	}

}
