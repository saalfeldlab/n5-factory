package org.janelia.saalfeldlab.n5.universe.factory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Paths;

import org.janelia.saalfeldlab.n5.N5Exception;
import org.janelia.saalfeldlab.n5.N5Path.N5DirectoryPath;
import org.janelia.saalfeldlab.n5.universe.factory.InferredStorageFormat.Source;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Expected results of parsing {@link N5Location}s, one example per case.
 * <p>
 * NB: Local paths are written as Unix paths. The expected {@code file:} URIs
 * are computed with {@link #file} / {@link #relative}, which is how the
 * canonical form is defined ({@code Path.normalize().toUri()}), so that the
 * tests also pass on Windows. On Unix, {@code file("/data/x")} is {@code
 * "file:///data/x"}.
 */
public class N5LocationTests {

	@Rule
	public TemporaryFolder tmp = new TemporaryFolder();

	/** The canonical {@code file:} URI of the absolute local path {@code path}. */
	private static String file(final String path) {
		return Paths.get(path).normalize().toUri().toString();
	}

	/** The canonical {@code file:} URI of {@code path}, relative to the working directory. */
	private static String relative(final String path) {
		return Paths.get(path).toAbsolutePath().normalize().toUri().toString();
	}

	private static void assertLocation(
			final String expectedUri,
			final String expectedContainerUri,
			final N5Location location) {

		assertEquals("uri", expectedUri, location.uri().toString());
		assertEquals("containerUri", expectedContainerUri, location.containerUri().toString());
	}

	private static void assertFormat(
			final StorageFormat expectedFormat,
			final Source expectedSource,
			final N5Location location) {

		assertEquals("format", expectedFormat, location.inferredFormat().format());
		assertEquals("source", expectedSource, location.inferredFormat().source());
	}

	// ------------------------------------------------------------------------
	//
	// -- of(String) --
	//

	@Test
	public void testOfStringAbsoluteUri() {

		// A well-formed URI is taken as is.
		assertLocation("s3://bucket/x", "s3://bucket/x", N5Location.of("s3://bucket/x"));
		assertLocation("s3://bucket/my%20x", "s3://bucket/my%20x", N5Location.of("s3://bucket/my%20x"));

		// A URI that is not well-formed is taken to be unencoded, and encoded.
		assertLocation("s3://bucket/my%20x", "s3://bucket/my%20x", N5Location.of("s3://bucket/my x"));

		// NB: Unencoded means all of it: a "%20" elsewhere in a malformed URI is quoted, too.
		assertLocation("s3://bucket/x?g%2520h#a%20b", "s3://bucket/x", N5Location.of("s3://bucket/x?g%20h#a b"));

		// Group (query) and attribute (fragment) are part of uri(), not of containerUri().
		assertLocation("https://host/a/b.zarr?g#a", "https://host/a/b.zarr", N5Location.of("https://host/a/b.zarr?g#a"));

		// Leading and trailing whitespace is trimmed.
		assertLocation("s3://bucket/x", "s3://bucket/x", N5Location.of("  s3://bucket/x  "));
	}

	@Test
	public void testOfStringStorageSchemePrefix() {

		N5Location location = N5Location.of("zarr3://s3://bucket/x");
		assertLocation("zarr3://s3://bucket/x", "s3://bucket/x", location);
		assertFormat(StorageFormat.ZARR3, Source.EXPLICIT, location);
		assertEquals(StorageFormat.ZARR3, location.explicitFormat());

		// The prefix is case-insensitive, uri() spells it canonically ...
		assertLocation("zarr3://s3://bucket/x", "s3://bucket/x", N5Location.of("ZARR3://s3://bucket/x"));
		// ... with "://" ...
		assertLocation("zarr://http://host/x", "http://host/x", N5Location.of("zarr:http://host/x"));
		// ... and all of a format's tokens map to that format.
		assertFormat(StorageFormat.HDF5, Source.EXPLICIT, N5Location.of("h5:/data/x.h5"));
		assertFormat(StorageFormat.HDF5, Source.EXPLICIT, N5Location.of("hdf5:/data/x.h5"));

		// A prefix also works in front of a local path.
		location = N5Location.of("h5:/data/x.h5");
		assertLocation("hdf5://" + file("/data/x.h5"), file("/data/x.h5"), location);

		// NB: The "//" after the prefix is consumed, so "n5://data/x" is the
		// relative path "data/x". "n5:///data/x" is the absolute path "/data/x".
		assertLocation("n5://" + relative("data/x"), relative("data/x"), N5Location.of("n5://data/x"));
		assertLocation("n5://" + file("/data/x"), file("/data/x"), N5Location.of("n5:///data/x"));
	}

	@Test
	public void testOfStringLocalPath() {

		assertLocation(file("/data/x.n5"), file("/data/x.n5"), N5Location.of("/data/x.n5"));

		// A relative path is resolved against the working directory.
		assertLocation(relative("data/x.n5"), relative("data/x.n5"), N5Location.of("data/x.n5"));

		// A path is unencoded: " " is encoded, and "%20" is taken literally.
		assertLocation(file("/data/my x"), file("/data/my x"), N5Location.of("/data/my x"));
		assertTrue(N5Location.of("/data/my x").uri().toString().endsWith("/data/my%20x"));
		assertLocation(relative("my%20x"), relative("my%20x"), N5Location.of("my%20x"));
		assertTrue(N5Location.of("my%20x").uri().toString().endsWith("/my%2520x"));
	}

	@Test
	public void testOfStringQueryAndFragment() {

		assertLocation(file("/data/x.zarr") + "?group/a#attr", file("/data/x.zarr"), N5Location.of("/data/x.zarr?group/a#attr"));

		// Query and fragment of a path are unencoded, too.
		assertLocation(file("/data/x") + "?a%20b#c%20d", file("/data/x"), N5Location.of("/data/x?a b#c d"));

		// The fragment starts at the last "#", the query at the first "?" before it.
		assertLocation(file("/data/x") + "?a?b%23c#d", file("/data/x"), N5Location.of("/data/x?a?b#c#d"));
	}

	@Test
	public void testOfStringNormalization() {

		// "." and ".." segments are removed.
		assertLocation(file("/data/x"), file("/data/x"), N5Location.of("/data/./a/../x"));
		assertLocation("s3://b/x", "s3://b/x", N5Location.of("s3://b/a/../x"));

		// All spellings of a local file URI end up in the form a path produces.
		final String expected = file("/data/x");
		assertLocation(expected, expected, N5Location.of("file:/data/x"));
		assertLocation(expected, expected, N5Location.of("file:///data/x"));
		assertLocation(expected, expected, N5Location.of("file://localhost/data/x"));
	}

	@Test
	public void testTrailingSlashDependsOnFileSystem() throws IOException {

		// NB: Path.toUri() appends "/" only to an existing directory, so the
		// container URI of a container changes when it is created.
		final File dir = tmp.newFolder("x.n5");
		assertTrue(N5Location.of(dir.toString()).containerUri().toString().endsWith("/x.n5/"));
		assertFalse(N5Location.of(dir + "-new").containerUri().toString().endsWith("/"));

		// A trailing "/" given for a path that does not exist is dropped.
		assertLocation(file("/data/x.zarr"), file("/data/x.zarr"), N5Location.of("/data/x.zarr/"));
	}

	@Test
	public void testOfStringRejects() {

		assertThrows(N5Exception.class, () -> N5Location.of(""));
		assertThrows(N5Exception.class, () -> N5Location.of("   "));
		assertThrows(N5Exception.class, () -> N5Location.of("zarr3://"));

		// opaque URI
		assertThrows(N5Exception.class, () -> N5Location.of("mailto:x"));

		// line break, almost certainly a paste accident
		assertThrows(N5Exception.class, () -> N5Location.of("/data/x\n/y"));
	}

	// ------------------------------------------------------------------------
	//
	// -- of(URI) --
	//

	@Test
	public void testOfUri() throws URISyntaxException {

		N5Location location = N5Location.of(new URI("zarr3://s3://b/x?g#a"));
		assertLocation("zarr3://s3://b/x?g#a", "s3://b/x", location);
		assertFormat(StorageFormat.ZARR3, Source.EXPLICIT, location);

		// A relative URI is a local path, resolved against the working directory.
		assertLocation(relative("data/x"), relative("data/x"), N5Location.of(new URI("data/x")));
		assertLocation("zarr3://" + relative("data/x"), relative("data/x"), N5Location.of(new URI("zarr3:data/x")));
		assertLocation("n5://" + file("/path"), file("/path"), N5Location.of(new URI("n5:/path")));

		// Unlike of(String), a URI is encoded: "%20" is a space.
		assertLocation(relative("a b"), relative("a b"), N5Location.of(new URI("a%20b")));
		assertLocation(relative("a b"), relative("a b"), N5Location.of(new URI(null, null, "a b", null)));
		assertLocation(file("/data/a b") + "?g%20h#x", file("/data/a b"), N5Location.of(new URI("/data/a%20b?g%20h#x")));

		// A network-path reference keeps its host: "//server/x" is passed to the
		// platform, which makes it /server/x on Unix and \\server\x on Windows.
		assertLocation(file("//server/x"), file("//server/x"), N5Location.of(new URI("//server/x")));
	}

	/**
	 * A drive letter after a leading slash, as {@link URI#getPath()} returns it
	 * for a {@code file:} URI on Windows. On Windows it is the drive; on Unix
	 * it is the directory {@code "C:"} below the root. Both give the same
	 * {@code file:} URI, so the expectations are platform independent.
	 */
	@Test
	public void testSlashDrive() throws URISyntaxException {

		// of(String) is unencoded, as URI.getPath() is.
		assertLocation("file:///C:/data/x?g#a", "file:///C:/data/x", N5Location.of("/C:/data/x?g#a"));
		assertLocation("n5://file:///C:/data/x", "file:///C:/data/x", N5Location.of("n5:/C:/data/x"));
		assertLocation("file:///C:/my%20x", "file:///C:/my%20x", N5Location.of("/C:/my x"));
		assertLocation("file:///C:/my%2520x", "file:///C:/my%2520x", N5Location.of("/C:/my%20x"));

		// of(URI) is encoded.
		assertLocation("file:///C:/my%20x", "file:///C:/my%20x", N5Location.of(new URI("/C:/my%20x")));

		// The form the n5-universe storage tests used to produce.
		final File dir = new File(tmp.getRoot(), "my x");
		assertEquals(file(dir.getPath()), N5Location.of(dir.toURI().getPath()).containerUri().toString());
	}

	@Test
	public void testSlashDriveWithoutSeparatorIsRejectedOnWindows() {

		org.junit.Assume.assumeTrue(File.separatorChar == '\\');
		// "C:" is relative to the current directory of drive C:, not its root.
		assertThrows(N5Exception.class, () -> N5Location.of("/C:"));
	}

	@Test
	public void testOfUriRoundTrip() throws URISyntaxException {

		for (final String input : new String[]{
				"zarr3://s3://bucket/x?group/a#attr",
				"s3://bucket/my x",
				"/data/my x?a b#c d",
				"my%20x",
				"h5:data/x.h5"}) {
			final N5Location location = N5Location.of(input);
			final N5Location roundTrip = N5Location.of(location.uri());
			assertEquals(input, location.uri(), roundTrip.uri());
			assertEquals(input, location.containerUri(), roundTrip.containerUri());
			assertEquals(input, location.inferredFormat(), roundTrip.inferredFormat());
		}
	}

	@Test
	public void testOfUriRejects() {

		assertThrows(N5Exception.class, () -> N5Location.of(new URI("")));
		assertThrows(N5Exception.class, () -> N5Location.of(new URI("mailto:x")));
	}

	// ------------------------------------------------------------------------
	//
	// -- ofContainer --
	//

	@Test
	public void testOfContainerUri() throws URISyntaxException {

		assertLocation("s3://b/x", "s3://b/x", N5Location.ofContainer(new URI("s3://b/x")));
		assertLocation(file("/data/x.n5"), file("/data/x.n5"), N5Location.ofContainer(new URI("file:/data/x.n5")));

		final N5Location location = N5Location.ofContainer(new URI("s3://b/x")).withFormat(StorageFormat.N5);
		assertLocation("n5://s3://b/x", "s3://b/x", location);
		assertFormat(StorageFormat.N5, Source.EXPLICIT, location);

		// Only an absolute, hierarchical URI without prefix, query, or fragment is a container URI.
		assertThrows(N5Exception.class, () -> N5Location.ofContainer(new URI("s3://b/x?g")));
		assertThrows(N5Exception.class, () -> N5Location.ofContainer(new URI("s3://b/x#a")));
		assertThrows(N5Exception.class, () -> N5Location.ofContainer(new URI("zarr3://s3://b/x")));
		assertThrows(N5Exception.class, () -> N5Location.ofContainer(new URI("data/x")));
		assertThrows(N5Exception.class, () -> N5Location.ofContainer(new URI("mailto:x")));
	}

	@Test
	public void testOfContainerPath() {

		// Nothing in a path is interpreted: "?" and "#" are part of the file name.
		final N5Location location = N5Location.ofContainer(Paths.get("/data/x?y#z.n5").toUri());
		assertLocation(file("/data/x?y#z.n5"), file("/data/x?y#z.n5"), location);
		assertNull(location.uri().getRawQuery());
		assertNull(location.uri().getRawFragment());
		assertFormat(StorageFormat.N5, Source.SUFFIX, location);

		// An explicit format overrides the suffix.
		final N5Location explicit = N5Location.ofContainer(Paths.get("/data/x.n5").toUri()).withFormat(StorageFormat.ZARR2);
		assertLocation("zarr2://" + file("/data/x.n5"), file("/data/x.n5"), explicit);
		assertFormat(StorageFormat.ZARR2, Source.EXPLICIT, explicit);
	}

	// ------------------------------------------------------------------------
	//
	// -- groupPath / attributePath --
	//

	@Test
	public void testGroupAndAttributePath() throws URISyntaxException {

		// Absent is null, not the root.
		final N5Location container = N5Location.of("/data/x.zarr");
		assertNull(container.groupPath());
		assertNull(container.attributePath());
		assertEquals(N5DirectoryPath.of(""), container.groupPathOrRoot());
		assertEquals("/", container.attributePathOrRoot());

		// Empty is the root group, and the empty attribute path.
		final N5Location empty = N5Location.of("/data/x.zarr?#");
		assertEquals(N5DirectoryPath.of(""), empty.groupPath());
		assertEquals("", empty.attributePath());

		final N5Location location = N5Location.of("zarr3://s3://b/x?a/b#c/d");
		assertEquals("a/b", location.groupPath().normalPath());
		assertEquals("c/d", location.attributePath());
		assertEquals(location.groupPath(), location.groupPathOrRoot());
		assertEquals(location.attributePath(), location.attributePathOrRoot());

		// The group path is normalized, the attribute path is not.
		assertEquals("a/c", N5Location.of("/data/x?/a//b/../c/").groupPath().normalPath());
		assertEquals("/a//b/../c", N5Location.of("/data/x#/a//b/../c").attributePath());

		// Decoded. A local path is unencoded, so its query is, too.
		assertEquals("a b", N5Location.of("/data/x?a b#c d").groupPath().normalPath());
		assertEquals("c d", N5Location.of("/data/x?a b#c d").attributePath());
		assertEquals("a%20b", N5Location.of("/data/x?a%20b").groupPath().normalPath());
		assertEquals("a b", N5Location.of(new URI("file:///data/x?a%20b")).groupPath().normalPath());
		assertEquals("a b", N5Location.of("s3://b/x?a%20b").groupPath().normalPath());

		// Escapes inside "[...]" are decoded, too.
		assertEquals("a/[\\]", N5Location.of(new URI("file:///data/x#a/[%5C]")).attributePath());

		// The fragment starts at the last "#", the query at the first "?" before it.
		final N5Location hashes = N5Location.of("/data/x?a?b#c#d");
		assertEquals("a?b#c", hashes.groupPath().normalPath());
		assertEquals("d", hashes.attributePath());

		assertEquals("zarr3://s3://b/x?a/b#c/d", location.toString());

		// Round trip.
		assertEquals(location.groupPath(), N5Location.of(location.uri()).groupPath());
		assertEquals(location.attributePath(), N5Location.of(location.uri()).attributePath());
	}

	@Test
	public void testWithGroupAndAttribute() {

		final N5Location location = N5Location.of("zarr3://s3://b/x?a/b#c/d");

		// Replaces the group, keeps prefix, container and attribute.
		assertEquals("zarr3://s3://b/x?e#c/d", location.withGroup("e").toString());
		assertEquals("zarr3://s3://b/x?e#c/d", location.withGroup(N5DirectoryPath.of("e")).toString());
		assertEquals("zarr3://s3://b/x?a/b#e", location.withAttribute("e").toString());
		assertEquals(location.inferredFormat(), location.withGroup("e").inferredFormat());

		// withFormat replaces the prefix, keeps group and attribute; null drops it.
		assertEquals("n5://s3://b/x?a/b#c/d", location.withFormat(StorageFormat.N5).toString());
		assertFormat(StorageFormat.N5, Source.EXPLICIT, location.withFormat(StorageFormat.N5));
		assertEquals("s3://b/x?a/b#c/d", location.withFormat(null).toString());
		assertFormat(StorageFormat.ZARR, Source.SUFFIX, N5Location.of("s3://b/x.zarr").withFormat(StorageFormat.ZARR3).withFormat(null));

		// null removes, "" is the root group / the empty attribute path.
		assertEquals("zarr3://s3://b/x#c/d", location.withGroup((String) null).toString());
		assertEquals("zarr3://s3://b/x?a/b", location.withAttribute(null).toString());
		assertEquals("zarr3://s3://b/x?#c/d", location.withGroup("").toString());
		assertEquals(N5DirectoryPath.of(""), location.withGroup("").groupPath());

		// Adds to a location without group or attribute.
		final N5Location container = N5Location.of("/data/x.zarr");
		assertEquals(file("/data/x.zarr") + "?a/b", container.withGroup("a/b").toString());
		assertEquals(file("/data/x.zarr") + "#c", container.withAttribute("c").toString());
		assertEquals(N5Location.of("/data/x.zarr?a/b#c"), container.withGroup("a/b").withAttribute("c"));

		// The group is normalized, the attribute path is not.
		assertEquals("zarr3://s3://b/x?a/c#c/d", location.withGroup("/a//b/../c/").toString());
		assertEquals("/a//b", location.withAttribute("/a//b").attributePath());

		// Not decoded, and encoded such that groupPath() / attributePath() give it back.
		for (final String name : new String[]{"my a", "my%20a", "a?b", "a#b", "a:b", "a/[0]", "ä"}) {
			assertEquals(name, location.withGroup(name).groupPath().normalPath());
			assertEquals(name, location.withAttribute(name).attributePath());
			final N5Location reparsed = N5Location.of(location.withGroup(name).withAttribute(name).uri());
			assertEquals(name, reparsed.groupPath().normalPath());
			assertEquals(name, reparsed.attributePath());
		}
		assertEquals("zarr3://s3://b/x?my%20a#c/d", location.withGroup("my a").toString());
		assertEquals("zarr3://s3://b/x?my%2520a#c/d", location.withGroup("my%20a").toString());
		assertEquals("zarr3://s3://b/x?a/b#a%23b", location.withAttribute("a#b").toString());
	}

	// ------------------------------------------------------------------------
	//
	// -- inferredFormat --
	//

	@Test
	public void testInferredFormat() {

		assertFormat(StorageFormat.N5, Source.SUFFIX, N5Location.of("/data/x.n5"));
		assertFormat(StorageFormat.HDF5, Source.SUFFIX, N5Location.of("/data/x.h5"));
		assertFormat(StorageFormat.HDF5, Source.SUFFIX, N5Location.of("/data/x.hdf5"));

		// The suffix is case-insensitive, and does not tell the zarr version.
		assertFormat(StorageFormat.ZARR, Source.SUFFIX, N5Location.of("/data/X.ZARR"));

		// The suffix is that of the container, not of the group.
		assertFormat(StorageFormat.ZARR, Source.SUFFIX, N5Location.of("s3://b/x.zarr?group.n5"));

		final N5Location unknown = N5Location.of("s3://b/x");
		assertFormat(null, Source.UNKNOWN, unknown);
		assertNull(unknown.explicitFormat());

		// A guessed format is not explicit.
		assertNull(N5Location.of("/data/x.n5").explicitFormat());
	}
}
