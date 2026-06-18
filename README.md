# control-bridge

![CI](https://github.com/memnos-dev/control-bridge/actions/workflows/ci.yml/badge.svg)

A thin, generic bridge plugin that connects a [Paper](https://papermc.io/) Minecraft
server (including [Citizens](https://citizensnpcs.co/) NPCs) to an external controller
over a JSON WebSocket protocol. The plugin is a dumb transport: it holds no game logic
and makes no outbound connections of its own beyond the single controller endpoint
configured by the server operator.

Built for the a platform under construction, but protocol-agnostic by design —
any controller speaking the documented WebSocket protocol can drive it.

## Status

Skeleton — no functional behaviour yet. The plugin loads and unloads cleanly;
WebSocket and Citizens integration follow in subsequent slices.

## Build

Requirements: **JDK 25** (Paper 26.1.x toolchain).