//
//  Logging.swift
//  NFCTest
//
//  Created by Andy Qua on 11/06/2019.
//  Copyright © 2019 Andy Qua. All rights reserved.
//

import Foundation
import OSLog

/// Internal logging facade shared by the protocol engine.
///
/// The upstream reader contains diagnostics with APDU payloads, protocol
/// nonces and derived session material. Compile all of those calls out of a
/// release NFCCore binary; only a Debug build may write them to unified log.
internal struct NfcDiagnosticLogger {
    #if DEBUG
    private let logger: Logger
    #endif

    init(category: String) {
        #if DEBUG
        let subsystem = Bundle.main.bundleIdentifier ?? "com.vppos.nfccore"
        self.logger = Logger(subsystem: subsystem, category: category)
        #endif
    }

    func debug(_ message: String) {
        #if DEBUG
        logger.debug("\(message, privacy: .public)")
        #endif
    }

    func info(_ message: String) {
        #if DEBUG
        logger.info("\(message, privacy: .public)")
        #endif
    }

    func warning(_ message: String) {
        #if DEBUG
        logger.warning("\(message, privacy: .public)")
        #endif
    }

    func error(_ message: String) {
        #if DEBUG
        logger.error("\(message, privacy: .public)")
        #endif
    }
}

extension Logger {
    /// Tag Reader logs
    static let passportReader = NfcDiagnosticLogger(category: "passportReader")

    /// Tag Reader logs
    static let tagReader = NfcDiagnosticLogger(category: "tagReader")

    /// SecureMessaging logs
    static let secureMessaging = NfcDiagnosticLogger(category: "secureMessaging")

    static let openSSL = NfcDiagnosticLogger(category: "openSSL")

    static let bac = NfcDiagnosticLogger(category: "BAC")
    static let chipAuth = NfcDiagnosticLogger(category: "chipAuthentication")
    static let pace = NfcDiagnosticLogger(category: "PACE")
}
