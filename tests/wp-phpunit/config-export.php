<?php
/*
 * Reads a PHPUnit 9 XML configuration with PHPUnit's own loader, on the reference PHP, and writes it serialized for
 * tests/wp-phpunit/phpunit.php, which hands it to PHPUnit in place of the XML on both engines: loading the XML
 * takes ext-dom and libxml, which Truffle PHP doesn't have. Changes to what the file says: the outcome listener
 * (Phpunit9Outcomes.php) is added, the configuration's own logging (a JUnit file in the tree) is dropped (run.sh
 * asks for JUnit on the command line where the engine can write it), the schema validation errors are dropped
 * (PHPUnit would re-read the XML to print its deprecated-schema warning), and with a chunk the test suites are cut
 * down to that chunk.
 *
 * Usage (from the project's root):
 *   php config-export.php phpunit.xml.dist out.ser             the whole configuration
 *   php config-export.php phpunit.xml.dist out.ser SUITE PATH  only PATH (a directory or file) of test suite SUITE,
 *                                                              with that suite's suffix, prefix and excludes
 *   php config-export.php phpunit.xml.dist --chunks [DIR]      the chunks, "SUITE<TAB>PATH" in the suites' order:
 *                                                              each entry of each suite directory, each suite file;
 *                                                              with DIR, chunk N's configuration as DIR/N.ser too
 */
use PHPUnit\TextUI\XmlConfiguration\Configuration;
use PHPUnit\TextUI\XmlConfiguration\Extension;
use PHPUnit\TextUI\XmlConfiguration\ExtensionCollection;
use PHPUnit\TextUI\XmlConfiguration\Loader;
use PHPUnit\TextUI\XmlConfiguration\Logging\Logging;
use PHPUnit\TextUI\XmlConfiguration\TestDirectory;
use PHPUnit\TextUI\XmlConfiguration\TestDirectoryCollection;
use PHPUnit\TextUI\XmlConfiguration\TestFile;
use PHPUnit\TextUI\XmlConfiguration\TestFileCollection;
use PHPUnit\TextUI\XmlConfiguration\TestSuite;
use PHPUnit\TextUI\XmlConfiguration\TestSuiteCollection;
use PHPUnit\Util\Xml\ValidationResult;

$xml = $argv[1];
$out = $argv[2];
require getcwd() . '/vendor/autoload.php';
$c = ( new Loader() )->load( $xml );

function excluded( TestSuite $suite, string $path ): bool {
	foreach ( $suite->exclude() as $file ) {
		if ( realpath( $file->path() ) === realpath( $path ) ) {
			return true;
		}
	}
	return false;
}

/** [suite name, path relative to the root] of each chunk. */
function chunks( Configuration $c ): array {
	$chunks = array();
	foreach ( $c->testSuite() as $suite ) {
		foreach ( $suite->directories() as $dir ) {
			foreach ( scandir( $dir->path() ) as $entry ) {
				$path = $dir->path() . '/' . $entry;
				if ( '.' === $entry[0] || excluded( $suite, $path )
					|| ( is_file( $path ) && ! str_ends_with( $entry, $dir->suffix() ) ) ) {
					continue;
				}
				$chunks[] = array( $suite->name(), substr( realpath( $path ), strlen( getcwd() ) + 1 ) );
			}
		}
		foreach ( $suite->files() as $file ) {
			$chunks[] = array( $suite->name(), substr( realpath( $file->path() ), strlen( getcwd() ) + 1 ) );
		}
	}
	return $chunks;
}

/** Suite $name cut down to $relative (a directory or file in it), or null if it isn't in that suite. */
function chunk_suites( Configuration $c, string $name, string $relative ): ?TestSuiteCollection {
	$path = realpath( $relative );
	foreach ( $c->testSuite() as $suite ) {
		if ( $suite->name() !== $name ) {
			continue;
		}
		$directories = array();
		$files       = array();
		foreach ( $suite->directories() as $dir ) {
			if ( 0 === strpos( $path . '/', realpath( $dir->path() ) . '/' ) ) {
				if ( is_dir( $path ) ) {
					$directories[] = new TestDirectory( $path, $dir->prefix(), $dir->suffix(), $dir->phpVersion(), $dir->phpVersionOperator() );
				} else {
					$files[] = new TestFile( $path, $dir->phpVersion(), $dir->phpVersionOperator() );
				}
				break;
			}
		}
		foreach ( $suite->files() as $file ) {
			if ( realpath( $file->path() ) === $path ) {
				$files[] = $file;
			}
		}
		if ( $directories || $files ) {
			$found = new TestSuite( $name, TestDirectoryCollection::fromArray( $directories ), TestFileCollection::fromArray( $files ), $suite->exclude() );
			return TestSuiteCollection::fromArray( array( $found ) );
		}
	}
	return null;
}

/** $c serialized, with the outcome listener, without logging and validation errors, with $suites as its suites. */
function export( Configuration $c, TestSuiteCollection $suites ): string {
	$c           = clone $c;
	$listeners   = $c->listeners()->asArray();
	$listeners[] = new Extension( 'Phpunit9Outcomes', __DIR__ . '/Phpunit9Outcomes.php', array() );
	// Configuration has no setters: its fields are replaced from inside its scope
	Closure::bind(
		function () use ( $listeners, $suites ) {
			$this->listeners = ExtensionCollection::fromArray( $listeners );
			$this->logging   = new Logging( null, null, null, null, null, null );
			$this->testSuite = $suites;
			// with validation errors PHPUnit re-reads the XML (ext-dom) to tell whether its schema is a deprecated one
			$this->validationResult = ValidationResult::fromArray( array() );
		},
		$c,
		Configuration::class
	)();
	return serialize( $c );
}

if ( '--chunks' === $out ) {
	foreach ( chunks( $c ) as $n => [ $name, $relative ] ) {
		echo $name, "\t", $relative, "\n";
		if ( isset( $argv[3] ) ) {
			file_put_contents( $argv[3] . '/' . ( $n + 1 ) . '.ser', export( $c, chunk_suites( $c, $name, $relative ) ) );
		}
	}
	exit( 0 );
}
$suites = $c->testSuite();
if ( isset( $argv[4] ) ) {
	$suites = chunk_suites( $c, $argv[3], $argv[4] );
	if ( null === $suites ) {
		fwrite( STDERR, "$argv[4] is not in test suite $argv[3] of $xml\n" );
		exit( 1 );
	}
}
file_put_contents( $out, export( $c, $suites ) );
