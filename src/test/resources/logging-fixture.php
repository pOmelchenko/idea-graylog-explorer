<?php
namespace {
    class Yii {
        public static function error($message, $category) {}
        public static function warning($message, $category) {}
    }
}
namespace DemoLogging {
    class Service {
        public function run() {
            $e = new \RuntimeException('Error processing items: bad value'); // construction
            \Yii::error($e->getMessage(), __METHOD__); // logger-one
            \Yii::warning('Import warning', __METHOD__); // logger-two
            $later = function () { \Yii::error('Nested closure', __METHOD__); };
        }
        public function constructionOnly() {
            throw new \RuntimeException('Error processing items: bad value');
        }
    }
    class CustomLogger {
        public function write($message) {}
    }
    class CustomService {
        public function run() {
            (new CustomLogger())->write('Custom message'); // custom-logger
        }
    }
}
namespace Psr\Log {
    interface LoggerInterface {
        public function warning($message, array $context = []);
    }
}
namespace DemoLogging {
    class PsrService {
        public function run(\Psr\Log\LoggerInterface $logger) {
            $logger->warning('Import warning'); // psr-logger
        }
    }
}
