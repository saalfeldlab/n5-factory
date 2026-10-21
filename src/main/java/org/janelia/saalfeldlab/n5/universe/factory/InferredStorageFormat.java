package org.janelia.saalfeldlab.n5.universe.factory;

import org.janelia.saalfeldlab.n5.KeyValueRoot;
import org.janelia.saalfeldlab.n5.N5Path.N5FilePath;

import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The {@link StorageFormat} inferred for a location: the format (if one could
 * be determined), and how the format was determined.
 * <p>
 * Build one with {@link #of(StorageFormat, URI)}, which looks at the URI only.
 * If the result is a mere guess ({@link #isExplicit()} is false), confirm it against
 * the container itself with {@link #resolveFromKeys}.
 */
public final class InferredStorageFormat {

	/**
	 * How the format was determined, in decreasing order of trust.
	 */
	public enum Source {

		/** An explicit scheme prefix, e.g. {@code "zarr3://…"} — the caller asked for this format. */
		EXPLICIT,

		/** Keys or file content at the location. Authoritative, but costs I/O. */
		CONTENT,

		/** The path suffix, e.g. {@code ".n5"}. A guess. */
		SUFFIX,

		/** Nothing determined a format; {@link #format()} is {@code null}. */
		UNKNOWN
	}

	private final StorageFormat format;
	private final Source source;

	private InferredStorageFormat(final StorageFormat format, final Source source) {

		this.format = format;
		this.source = Objects.requireNonNull(source);
	}

	/**
	 * Return the inferred {@code StorageFormat}.
	 * <p>
	 * If no format could be inferred, {@code format()} is {@code null}, and {@link #source()} is {@link Source#UNKNOWN}.
	 *
	 * @return the inferred format or {@code null}
	 */
	public StorageFormat format() {

		return format;
	}

	/**
	 * Return how the inferred {@code StorageFormat} was obtained.
	 * <p>
	 * If no format could be inferred, {@link #format()} is {@code null}, and {@code source()} is {@link Source#UNKNOWN}.
	 *
	 * @return how {@link #format()} was determined
	 */
	public Source source() {

		return source;
	}

	/**
	 * Whether the format was stated explicitly, as a scheme prefix.
	 * Such a format should be used as-is; anything else is a guess.
	 *
	 * @return true if the format came from a scheme prefix
	 */
	public boolean isExplicit() {

		return source == Source.EXPLICIT;
	}

	/**
	 * Confirm a guessed format against what is actually at the location.
	 * <p>
	 * Returns {@code this} when the format was explicit (nothing to confirm) or
	 * when probing finds nothing; otherwise a new
	 * {@code InferredStorageFormat} with the probed format and
	 * {@link Source#CONTENT}. Probing is the only way to tell Zarr v2 from v3.
	 *
	 * @param root
	 * 		the container to probe. It is expected to be rooted at the location that
	 * 		provided this InferredStorageFormat. The keys are looked up relative to it.
	 *
	 * @return this, or the probed result
	 */
	public InferredStorageFormat resolveFromKeys(final KeyValueRoot root) {

		if (isExplicit())
			return this;

		final StorageFormat fromKeys = fromKeys(root);
		return fromKeys != null ? new InferredStorageFormat(fromKeys, Source.CONTENT) : this;
	}

	@Override
	public boolean equals(final Object o) {

		if (this == o)
			return true;
		if (!(o instanceof InferredStorageFormat))
			return false;
		final InferredStorageFormat other = (InferredStorageFormat)o;
		return format == other.format && source == other.source;
	}

	@Override
	public int hashCode() {

		return Objects.hash(format, source);
	}

	@Override
	public String toString() {

		return "InferredStorageFormat[format=" + format + ", source=" + source + "]";
	}

	/**
	 * @return a new {@link Source#UNKNOWN} {@code InferredStorageFormat}.
	 */
	public static InferredStorageFormat unknown() {

		return new InferredStorageFormat(null, Source.UNKNOWN);
	}

	/**
	 * @return a new explicit {@code InferredStorageFormat}.
	 */
	public static InferredStorageFormat of(final StorageFormat format) {

		if (format != null)
			return new InferredStorageFormat(format, Source.EXPLICIT);
		return unknown();
	}

	// ------------------------------------------------------------------------
	//
	// -- inference --
	//

	/**
	 * Creates an InferredStorageFormat, by trying to guess the format from the suffix of {@code containerUri}.
	 * If successful, returns {@code (format, SUFFIX)}.
	 * If nothing can be guessed, returns {@code (null, UNKNOWN)}.
	 *
	 * @param containerUri the containerUri (no query or fragment) for guessing from suffix.
	 * @return the InferredStorageFormat
	 */
	static InferredStorageFormat of(final URI containerUri) {

		return of(null, containerUri);
	}

	/**
	 * Creates an InferredStorageFormat.
	 * If {@code explicitFormat} is non-{@code null}, returns {@code (explicitFormat, EXPLICIT)}.
	 * Otherwise, tries to guess the format from the suffix of {@code containerUri} and returns {@code (format, SUFFIX)}.
	 * Otherwise, returns {@code (null, UNKNOWN)}.
	 *
	 * @param explicitFormat the {@code StorageFormat} if known, or {@code null}.
	 * @param containerUri the containerUri (no query or fragment) for guessing from suffix.
	 * @return the InferredStorageFormat
	 */
	static InferredStorageFormat of(final StorageFormat explicitFormat, final URI containerUri) {

		if (explicitFormat != null)
			return new InferredStorageFormat(explicitFormat, Source.EXPLICIT);

		final StorageFormat fromSuffix = fromSuffix(containerUri);
		return fromSuffix != null
				? new InferredStorageFormat(fromSuffix, Source.SUFFIX)
				: new InferredStorageFormat(null, Source.UNKNOWN);
	}

	private static final Pattern ZARR_SUFFIX = Pattern.compile("\\.zarr/*$", Pattern.CASE_INSENSITIVE);
	private static final Pattern N5_SUFFIX = Pattern.compile("\\.n5/*$", Pattern.CASE_INSENSITIVE);
	private static final Pattern HDF5_SUFFIX = Pattern.compile("\\.h(?:df)?5/*$", Pattern.CASE_INSENSITIVE);
	// NB: The URI of a directory may end in a slash, therefore the trailing "/*" in the patterns.
	// (In fact, FileSystemKeyValueRoot.uri() always ends in a slash.)

	private static final String[] ZARR2_KEYS = {".zarray", ".zgroup", ".zattrs"};
	private static final String ZARR3_ATTRIBUTES = "zarr.json";
	private static final String N5_ATTRIBUTES = "attributes.json";

	/**
	 * A {@code ".zarr"} suffix says nothing about the version, so this answers
	 * {@link StorageFormat#ZARR} and leaves the version to be resolved later.
	 */
	private static StorageFormat fromSuffix(final URI uri) {

		final String path = uri.getPath();
		if (path == null)
			// opaque URI, e.g. "mailto:" — no path to match a suffix against
			return null;

		if (ZARR_SUFFIX.matcher(path).find())
			return StorageFormat.ZARR;
		if (N5_SUFFIX.matcher(path).find())
			return StorageFormat.N5;
		if (HDF5_SUFFIX.matcher(path).find())
			return StorageFormat.HDF5;
		return null;
	}

	private static StorageFormat fromKeys(final KeyValueRoot root) {

		// A local HDF5 file can be wrapped in a KeyValueRoot but is not a
		// container, so this has to be decided before any key lookup. Only for
		// local roots: an S3/GCS/HTTP root has a path too, but it names no local file.
		// NB: Paths.get(URI), not getPath(): "/C:/x" is not a valid path on Windows.
		// The trailing "/" of a FileSystemKeyValueRoot URI is dropped by Paths.
		final URI rootUri = root.uri();
		if ("file".equalsIgnoreCase(rootUri.getScheme())
				&& isHdf5File(Paths.get(rootUri).toString()))
			return StorageFormat.HDF5;

		if (root.isFile(N5FilePath.of(ZARR3_ATTRIBUTES)))
			return StorageFormat.ZARR3;
		if (Arrays.stream(ZARR2_KEYS).anyMatch(key -> root.isFile(N5FilePath.of(key))))
			return StorageFormat.ZARR2;
		if (root.isFile(N5FilePath.of(N5_ATTRIBUTES)))
			return StorageFormat.N5;
		return null;
	}

	/** The 8-byte HDF5 signature, as in {@code HDF5Utils.HDF5_SIG}. */
	private static final byte[] HDF5_SIG = {(byte)137, 72, 68, 70, 13, 10, 26, 10};

	/**
	 * Check whether {@code path} is a regular file that starts with the
	 * HDF5 signature.
	 * <p>
	 * Inlined from HDF5Utils.isHDF5 (n5-hdf5) so that recognising an HDF5
	 * container needs no HDF5 dependency. We want to be able to detect HDF5
	 * even if we cannot open it.
	 *
	 * @param path path to check
	 * @return true if {@code path} is an existing regular file whose first eight
	 * 		bytes are the HDF5 signature; false for a missing, non-regular,
	 * 		shorter or unreadable file
	 */
	private static boolean isHdf5File(final String path) {

		final File file = new File(path);
		if (!file.isFile())
			return false;

		try (final DataInputStream in = new DataInputStream(new FileInputStream(file))) {
			final byte[] sig = new byte[HDF5_SIG.length];
			in.readFully(sig);
			return Arrays.equals(sig, HDF5_SIG);
		} catch (final IOException e) {
			return false;
		}
	}
}
