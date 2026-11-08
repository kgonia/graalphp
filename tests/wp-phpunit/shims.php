<?php
/*
 * Built-ins that PHPUnit's runner itself calls and Truffle PHP lacks, written in PHP, each declared only where the
 * engine has none (so on PHP this file declares nothing). Without them no test would run; with them, a test or
 * WordPress code that calls one of them runs on the shim, as close to php-src as the runner needs. phpunit.php
 * loads this file unless WP_PHPUNIT_SHIMS=0, and prints the names it declared to stderr: each is a gap to fix in
 * the runtime.
 */
$wp_phpunit_shims = array();

if ( ! function_exists( 'libxml_clear_errors' ) ) {
	$wp_phpunit_shims[] = 'libxml_clear_errors, libxml_use_internal_errors, libxml_get_errors, libxml_get_last_error';
	/*
	 * ext-libxml's error buffer, for an engine without any XML parser: it never holds an error. PHPUnit clears it
	 * after every test (TestCase::runBare).
	 */
	function libxml_clear_errors(): void {
	}

	function libxml_use_internal_errors( ?bool $use_errors = null ): bool {
		static $current = false;
		$previous = $current;
		if ( null !== $use_errors ) {
			$current = $use_errors;
		}
		return $previous;
	}

	function libxml_get_errors(): array {
		return array();
	}

	function libxml_get_last_error() {
		return false;
	}
}

if ( ! function_exists( 'get_include_path' ) ) {
	$wp_phpunit_shims[] = 'get_include_path, set_include_path';
	/* php-src's: the include_path setting (PHPUnit passes it to a process-isolated test). */
	function get_include_path() {
		return ini_get( 'include_path' );
	}

	function set_include_path( string $include_path ) {
		return ini_set( 'include_path', $include_path );
	}
}

if ( ! function_exists( 'stream_resolve_include_path' ) ) {
	$wp_phpunit_shims[] = 'stream_resolve_include_path';
	/** php-src's: an absolute or ./ ../ path against the working directory, else each include_path entry. */
	function stream_resolve_include_path( string $filename ) {
		if ( '' === $filename ) {
			return false;
		}
		$candidates = array();
		if ( '/' === $filename[0] || 0 === strncmp( $filename, './', 2 ) || 0 === strncmp( $filename, '../', 3 ) ) {
			$candidates[] = $filename;
		} else {
			foreach ( explode( PATH_SEPARATOR, (string) ini_get( 'include_path' ) ) as $dir ) {
				if ( '' !== $dir ) {
					$candidates[] = rtrim( $dir, '/' ) . '/' . $filename;
				}
			}
		}
		foreach ( $candidates as $candidate ) {
			if ( file_exists( $candidate ) ) {
				return realpath( $candidate );
			}
		}
		return false;
	}
}

if ( ! function_exists( 'chdir' ) ) {
	$wp_phpunit_shims[] = 'chdir';
	/**
	 * Only to the working directory itself: PHP code can't change the process's. PHPUnit's runner calls it with the
	 * directory it is in; any other directory is reported on stderr, with its caller, and fails as php-src's
	 * chdir() fails (a warning, false).
	 */
	function chdir( string $directory ): bool {
		$target = realpath( $directory );
		if ( false !== $target && $target === getcwd() ) {
			return true;
		}
		$caller = debug_backtrace( DEBUG_BACKTRACE_IGNORE_ARGS, 2 )[0];
		fwrite( STDERR, "wp-phpunit shim chdir($directory) not done, from {$caller['file']}:{$caller['line']}\n" );
		trigger_error( "chdir(): No such file or directory (errno 2)", E_USER_WARNING );
		return false;
	}
}

if ( ! class_exists( 'AppendIterator', false ) ) {
	$wp_phpunit_shims[] = 'AppendIterator';
	/**
	 * SPL's, for php-file-iterator (PHPUnit's list of test files): the appended iterators one after another, each
	 * rewound when reached. Not an IteratorIterator, as php-src's is.
	 */
	class AppendIterator implements OuterIterator {
		private array $iterators = array();
		private int $index       = 0;

		public function append( Iterator $iterator ): void {
			$this->iterators[] = $iterator;
			if ( ! $this->valid() ) {
				if ( count( $this->iterators ) - 1 === $this->index ) {
					$iterator->rewind();
				}
				$this->skip_ended();
			}
		}

		public function rewind(): void {
			$this->index = 0;
			if ( $this->iterators ) {
				$this->iterators[0]->rewind();
				$this->skip_ended();
			}
		}

		public function valid(): bool {
			return isset( $this->iterators[ $this->index ] ) && $this->iterators[ $this->index ]->valid();
		}

		public function current(): mixed {
			return $this->valid() ? $this->iterators[ $this->index ]->current() : null;
		}

		public function key(): mixed {
			return $this->valid() ? $this->iterators[ $this->index ]->key() : null;
		}

		public function next(): void {
			if ( isset( $this->iterators[ $this->index ] ) ) {
				$this->iterators[ $this->index ]->next();
				$this->skip_ended();
			}
		}

		public function getInnerIterator(): ?Iterator {
			return $this->iterators[ $this->index ] ?? null;
		}

		public function getIteratorIndex(): ?int {
			return isset( $this->iterators[ $this->index ] ) ? $this->index : null;
		}

		public function getArrayIterator(): ArrayIterator {
			return new ArrayIterator( $this->iterators );
		}

		/** Moves past ended iterators, rewinding each next one. */
		private function skip_ended(): void {
			while ( isset( $this->iterators[ $this->index ] ) && ! $this->iterators[ $this->index ]->valid()
				&& isset( $this->iterators[ $this->index + 1 ] ) ) {
				++$this->index;
				$this->iterators[ $this->index ]->rewind();
			}
		}
	}
}

if ( ! class_exists( 'RecursiveFilterIterator', false ) ) {
	$wp_phpunit_shims[] = 'RecursiveFilterIterator';
	/** SPL's, for PHPUnit's group and name filters. */
	abstract class RecursiveFilterIterator extends FilterIterator implements RecursiveIterator {
		public function __construct( RecursiveIterator $iterator ) {
			parent::__construct( $iterator );
		}

		public function hasChildren(): bool {
			return $this->getInnerIterator()->hasChildren();
		}

		public function getChildren(): ?RecursiveFilterIterator {
			return new static( $this->getInnerIterator()->getChildren() );
		}
	}
}

// a process-isolated test's child (truffle-php.sh) stays quiet: PHPUnit takes its stderr for an error
if ( $wp_phpunit_shims && ! defined( 'WP_PHPUNIT_QUIET_SHIMS' ) ) {
	fwrite( STDERR, 'wp-phpunit shims (built-ins this engine lacks): ' . implode( ', ', $wp_phpunit_shims ) . "\n" );
}
unset( $wp_phpunit_shims );
