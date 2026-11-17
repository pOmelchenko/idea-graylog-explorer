<?php
namespace GraylogHintFixture;
class TextException extends \RuntimeException {
    public function __construct(string $text) { parent::__construct(sprintf('Bad: %s!', $text)); }
}
class ChangedException extends \RuntimeException {
    public function __construct(string $text) { $text = trim($text); parent::__construct(sprintf('Bad: %s!', $text)); }
}
class ParentChanges extends \RuntimeException {
    public function __construct(string $text) { parent::__construct(strtoupper($text)); }
}
class InheritedChange extends ParentChanges {
    public function __construct(string $text) { parent::__construct(sprintf('Bad: %s!', $text)); }
}
class TwoSlots extends \RuntimeException {
    public function __construct(string $text) { parent::__construct(sprintf('%s %s', $text, $text)); }
}
class Precision extends \RuntimeException {
    public function __construct(string $text) { parent::__construct(sprintf('Bad: %.3s!', $text)); }
}
class ShadowedFunction extends \RuntimeException {
    public function __construct(string $text) { parent::__construct(fakeSprintf('Bad: %s!', $text)); }
}
function fakeSprintf($format, $text) { return $text; }
function renamed(string $input) { throw new TextException($input); } // case:rename
function expression(string $input) { throw new TextException(trim($input)); } // case:expression
function constant() { throw new TextException('fixed'); } // case:constant
function ambiguous(string $input, bool $flag) { if ($flag) throw new TextException($input); else throw new TextException($input); } // case:ambiguous
function changed(string $input) { throw new ChangedException($input); } // case:changed
function inherited(string $input) { throw new InheritedChange($input); } // case:inherited
function slots(string $input) { throw new TwoSlots($input); } // case:slots
function precision(string $input) { throw new Precision($input); } // case:precision
function shadowed(string $input) { throw new ShadowedFunction($input); } // case:shadowed
function named(string $input) { throw new TextException(text: $input); } // case:named
function different(string $input) { throw new \LogicException($input); } // case:different
function noThrow(string $input) { echo $input; } // case:noThrow
