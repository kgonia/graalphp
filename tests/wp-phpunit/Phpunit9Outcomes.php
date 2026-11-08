<?php
/**
 * A PHPUnit 9 listener that writes each test's outcome as it ends, one line per test, for tests/wp-phpunit/compare.py:
 *
 *   <Class>::<name, with its data set><TAB><outcome><TAB><seconds><TAB><first line of the message>
 *
 * The outcome is the worst PHPUnit reported for the test: error, failure, warning, risky, incomplete, skip, else
 * pass. JUnit's log can't tell risky from pass, nor incomplete from skipped, and is written only when the run ends;
 * these lines are flushed one by one, so a run that dies still has every test before the one it died in, which is
 * named by the "#start" line written before each test.
 *
 * Configured by run.sh in a copy of the suite's phpunit.xml (a <listener> with this file); the output path comes
 * from the PHPUNIT_OUTCOMES environment variable.
 */

use PHPUnit\Framework\AssertionFailedError;
use PHPUnit\Framework\ExceptionWrapper;
use PHPUnit\Framework\Test;
use PHPUnit\Framework\TestCase;
use PHPUnit\Framework\TestListener;
use PHPUnit\Framework\TestListenerDefaultImplementation;
use PHPUnit\Framework\TestSuite;
use PHPUnit\Framework\Warning;

final class Phpunit9Outcomes implements TestListener {
	use TestListenerDefaultImplementation;

	private const RANK = array( 'pass' => 0, 'skip' => 1, 'incomplete' => 2, 'risky' => 3, 'warning' => 4, 'failure' => 5, 'error' => 6 );

	/** @var resource */
	private $out;
	private string $outcome = 'pass';
	private string $message = '';

	public function __construct() {
		$path = getenv( 'PHPUNIT_OUTCOMES' );
		$this->out = fopen( false === $path || '' === $path ? 'php://stderr' : $path, 'ab' );
	}

	public function startTest( Test $test ): void {
		$this->outcome = 'pass';
		$this->message = '';
		$this->write( '#start ' . self::name( $test ) );
	}

	public function addError( Test $test, Throwable $t, float $time ): void {
		$this->note( 'error', $t );
	}

	public function addWarning( Test $test, Warning $e, float $time ): void {
		$this->note( 'warning', $e );
	}

	public function addFailure( Test $test, AssertionFailedError $e, float $time ): void {
		$this->note( 'failure', $e );
	}

	public function addIncompleteTest( Test $test, Throwable $t, float $time ): void {
		$this->note( 'incomplete', $t );
	}

	public function addRiskyTest( Test $test, Throwable $t, float $time ): void {
		$this->note( 'risky', $t );
	}

	public function addSkippedTest( Test $test, Throwable $t, float $time ): void {
		$this->note( 'skip', $t );
	}

	public function endTest( Test $test, float $time ): void {
		$line = self::name( $test ) . "\t" . $this->outcome . "\t" . sprintf( '%.3f', $time ) . "\t" . $this->message;
		$this->write( $line );
	}

	private function note( string $outcome, Throwable $t ): void {
		if ( self::RANK[ $outcome ] <= self::RANK[ $this->outcome ] ) {
			return;
		}
		$this->outcome = $outcome;
		$class         = $t instanceof ExceptionWrapper ? $t->getClassName() : get_class( $t );
		// a @requires skip starts with PHPUnit's "__OFFSET_LINE=N" line, then what is missing
		$text          = trim( preg_replace( '/^__OFFSET_LINE=\d+\s*/', '', $t->getMessage() ) );
		$text          = trim( strtok( $text, "\n" ) ?: '' );
		$this->message = substr( preg_replace( '/\s+/', ' ', $class . ': ' . $text ), 0, 400 );
	}

	private static function name( Test $test ): string {
		if ( $test instanceof TestCase ) {
			$name = get_class( $test ) . '::' . $test->getName( true );
		} else {
			// a suite skipped or failed as a whole (a data provider's: Class::method)
			$name = $test instanceof TestSuite ? $test->getName() : get_class( $test );
		}
		return str_replace( "\t", ' ', $name );
	}

	private function write( string $line ): void {
		fwrite( $this->out, str_replace( array( "\r", "\n" ), ' ', $line ) . "\n" );
		fflush( $this->out );
	}
}
