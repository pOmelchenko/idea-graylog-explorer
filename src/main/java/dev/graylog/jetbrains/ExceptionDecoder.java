package dev.graylog.jetbrains;

import java.util.List;

/** Maps one log schema to the common immutable exception chain. No IDE or language PSI. */
interface ExceptionDecoder { List<ExceptionDetails.Part> decode(LogEvent event); }
