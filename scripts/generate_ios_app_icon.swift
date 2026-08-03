#!/usr/bin/env swift

import CoreGraphics
import Foundation
import ImageIO
import UniformTypeIdentifiers

private let canvasSize = 1024
private let viewportSize: CGFloat = 108
private let scale = CGFloat(canvasSize) / viewportSize

private func color(_ hex: UInt32, alpha: CGFloat = 1) -> CGColor {
    CGColor(
        red: CGFloat((hex >> 16) & 0xff) / 255,
        green: CGFloat((hex >> 8) & 0xff) / 255,
        blue: CGFloat(hex & 0xff) / 255,
        alpha: alpha
    )
}

private func drawIcon(in context: CGContext) {
    context.translateBy(x: 0, y: CGFloat(canvasSize))
    context.scaleBy(x: scale, y: -scale)
    context.setAllowsAntialiasing(true)
    context.setShouldAntialias(true)

    let colorSpace = CGColorSpace(name: CGColorSpace.sRGB)!
    let background = CGGradient(
        colorsSpace: colorSpace,
        colors: [color(0x18231E), color(0x060908)] as CFArray,
        locations: [0, 1]
    )!
    context.drawLinearGradient(
        background,
        start: CGPoint(x: 0, y: 0),
        end: CGPoint(x: viewportSize, y: viewportSize),
        options: []
    )

    context.saveGState()
    context.setStrokeColor(color(0x00E676, alpha: 0.07))
    context.setLineWidth(0.6)
    for position in stride(from: CGFloat(9), through: CGFloat(99), by: 9) {
        context.move(to: CGPoint(x: 0, y: position))
        context.addLine(to: CGPoint(x: viewportSize, y: position))
        context.move(to: CGPoint(x: position, y: 0))
        context.addLine(to: CGPoint(x: position, y: viewportSize))
    }
    context.strokePath()
    context.restoreGState()

    let screenRect = CGRect(x: 29, y: 33, width: 50, height: 42)
    let screen = CGPath(roundedRect: screenRect, cornerWidth: 9, cornerHeight: 9, transform: nil)
    context.addPath(screen)
    context.setFillColor(color(0x0B100E))
    context.fillPath()

    context.addPath(screen)
    context.setStrokeColor(color(0x00E676))
    context.setLineWidth(2.8)
    context.strokePath()

    context.setStrokeColor(color(0x00E676, alpha: 0.35))
    context.setLineWidth(1.6)
    context.move(to: CGPoint(x: 29, y: 44))
    context.addLine(to: CGPoint(x: 79, y: 44))
    context.strokePath()

    context.setFillColor(color(0x00E676, alpha: 0.55))
    for centerX in [CGFloat(35.5), 41, 46.5] {
        context.fillEllipse(in: CGRect(x: centerX - 1.5, y: 37, width: 3, height: 3))
    }

    context.setStrokeColor(color(0x4DFF9F))
    context.setLineWidth(3.4)
    context.setLineCap(.round)
    context.setLineJoin(.round)
    context.move(to: CGPoint(x: 36.5, y: 53))
    context.addLine(to: CGPoint(x: 42, y: 58.5))
    context.addLine(to: CGPoint(x: 36.5, y: 64))
    context.strokePath()

    let cursor = CGPath(
        roundedRect: CGRect(x: 48, y: 54.8, width: 14, height: 7.4),
        cornerWidth: 1.6,
        cornerHeight: 1.6,
        transform: nil
    )
    context.addPath(cursor)
    context.setFillColor(color(0x4DFF9F))
    context.fillPath()
}

guard CommandLine.arguments.count == 2 else {
    FileHandle.standardError.write(Data("Usage: generate_ios_app_icon.swift OUTPUT.png\n".utf8))
    exit(2)
}

let colorSpace = CGColorSpace(name: CGColorSpace.sRGB)!
guard let context = CGContext(
    data: nil,
    width: canvasSize,
    height: canvasSize,
    bitsPerComponent: 8,
    bytesPerRow: canvasSize * 4,
    space: colorSpace,
    bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue
) else {
    fatalError("Unable to create the icon bitmap context")
}

drawIcon(in: context)
guard let image = context.makeImage() else {
    fatalError("Unable to create the icon image")
}

let outputURL = URL(fileURLWithPath: CommandLine.arguments[1])
guard let destination = CGImageDestinationCreateWithURL(
    outputURL as CFURL,
    UTType.png.identifier as CFString,
    1,
    nil
) else {
    fatalError("Unable to create the PNG destination")
}

CGImageDestinationAddImage(destination, image, nil)
guard CGImageDestinationFinalize(destination) else {
    fatalError("Unable to write \(outputURL.path)")
}
