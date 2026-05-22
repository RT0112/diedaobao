#!/usr/bin/env python3
"""
Anthropic-to-OpenAI Translation Proxy for Claude Code + qclaw

Makes Claude Code CLI think it's talking to the Anthropic API,
while actually routing through qclaw's free-tier gateway.

Usage:
  python3 proxy.py [--port 20000] [--qclaw-url http://127.0.0.1:19000/proxy/llm/chat/completions]

Then set for Claude Code:
  export ANTHROPIC_BASE_URL=http://127.0.0.1:20000
  export ANTHROPIC_API_KEY=qclaw-proxy
"""

import asyncio
import json
import logging
import time
import uuid
import argparse
from typing import Any, Optional
from aiohttp import web, ClientSession

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(message)s",
    handlers=[
        logging.FileHandler("/tmp/proxy_20000.log", mode="a"),
        logging.StreamHandler(),
    ],
)
log = logging.getLogger("anthropic-proxy")

# ── Config ──────────────────────────────────────────────

QCLAW_URL = "http://127.0.0.1:19000/proxy/llm/chat/completions"
QCLAW_AUTH = "Bearer __QCLAW_AUTH_GATEWAY_MANAGED__"
QCLAW_MODEL = "modelroute"

<<<<<<< Updated upstream
=======
# Timeout: 30s connect, 5min total (CC can take a while to think)
_PROXY_TIMEOUT = ClientTimeout(total=1800, connect=30)  # 30 min — CC compilation can take 5-10 min

>>>>>>> Stashed changes
# ── Anthropic → OpenAI: Request Translation ─────────────

def translate_messages(anthropic_messages: list, system: Optional[Any] = None) -> list:
    """Convert Anthropic messages to OpenAI format."""
    openai_msgs = []

    # System prompt
    if system:
        if isinstance(system, str):
            openai_msgs.append({"role": "system", "content": system})
        elif isinstance(system, list):
            # Anthropic system can be content blocks
            text_parts = []
            for block in system:
                if isinstance(block, dict) and block.get("type") == "text":
                    text_parts.append(block["text"])
                elif isinstance(block, str):
                    text_parts.append(block)
            if text_parts:
                openai_msgs.append({"role": "system", "content": "\n".join(text_parts)})

    for msg in anthropic_messages:
        role = msg["role"]
        content = msg["content"]

        if role == "user":
            if isinstance(content, str):
                openai_msgs.append({"role": "user", "content": content})
            elif isinstance(content, list):
                # Handle content blocks
                text_parts = []
                tool_results = []
                for block in content:
                    if isinstance(block, dict):
                        if block.get("type") == "text":
                            text_parts.append(block["text"])
                        elif block.get("type") == "tool_result":
                            tool_results.append(block)
                        elif block.get("type") == "image":
                            # Image block - pass through as text description
                            text_parts.append("[image content not supported via qclaw gateway]")

                if tool_results:
                    # Convert tool_result blocks to OpenAI tool message
                    tool_content_parts = []
                    for tr in tool_results:
                        tc = tr.get("content", "")
                        if isinstance(tc, list):
                            for sub in tc:
                                if isinstance(sub, dict) and sub.get("type") == "text":
                                    tool_content_parts.append(sub["text"])
                        elif isinstance(tc, str):
                            tool_content_parts.append(tc)

                    openai_msgs.append({
                        "role": "tool",
                        "tool_call_id": str(tool_results[0].get("tool_use_id", "unknown")),
                        "content": "\n".join(tool_content_parts) if tool_content_parts else "",
                    })

                if text_parts:
                    openai_msgs.append({"role": "user", "content": "\n".join(text_parts)})

        elif role == "assistant":
            if isinstance(content, str):
                openai_msgs.append({"role": "assistant", "content": content})
            elif isinstance(content, list):
                text_parts = []
                tool_calls = []
                for block in content:
                    if isinstance(block, dict):
                        if block.get("type") == "text":
                            text_parts.append(block["text"])
                        elif block.get("type") == "tool_use":
                            tool_calls.append({
                                "id": block.get("id", f"call_{uuid.uuid4().hex[:24]}"),
                                "type": "function",
                                "function": {
                                    "name": block["name"],
                                    "arguments": json.dumps(block.get("input", {})),
                                },
                            })
                        elif block.get("type") == "thinking":
                            # Extended thinking - skip for OpenAI compatibility
                            pass

                assistant_msg = {"role": "assistant"}
                if text_parts:
                    assistant_msg["content"] = "\n".join(text_parts)
                else:
                    assistant_msg["content"] = None
                if tool_calls:
                    assistant_msg["tool_calls"] = tool_calls
                openai_msgs.append(assistant_msg)

    return openai_msgs


def translate_tools(anthropic_tools: list) -> list:
    """Convert Anthropic tool definitions to OpenAI function format."""
    openai_tools = []
    for tool in anthropic_tools:
        if tool.get("type") == "custom":
            # Skip custom tool types that OpenAI doesn't understand
            continue
        openai_tools.append({
            "type": "function",
            "function": {
                "name": tool["name"],
                "description": tool.get("description", ""),
                "parameters": tool.get("input_schema", {"type": "object", "properties": {}}),
            },
        })
    return openai_tools


def translate_request(anthropic_body: dict) -> dict:
    """Full Anthropic → OpenAI request translation."""
    openai_body = {
        "model": QCLAW_MODEL,  # Always override to qclaw model
        "messages": translate_messages(
            anthropic_body.get("messages", []),
            system=anthropic_body.get("system"),
        ),
        "max_tokens": anthropic_body.get("max_tokens", 4096),
        "stream": anthropic_body.get("stream", False),
    }

    # Tools
    if "tools" in anthropic_body:
        openai_tools = translate_tools(anthropic_body["tools"])
        if openai_tools:
            openai_body["tools"] = openai_tools

    # Optional params
    if "temperature" in anthropic_body:
        openai_body["temperature"] = anthropic_body["temperature"]
    if "top_p" in anthropic_body:
        openai_body["top_p"] = anthropic_body["top_p"]
    if "stop_sequences" in anthropic_body:
        openai_body["stop"] = anthropic_body["stop_sequences"]

    return openai_body


# ── OpenAI → Anthropic: Response Translation ─────────────

def map_stop_reason(finish_reason: Optional[str]) -> str:
    """Map OpenAI finish_reason to Anthropic stop_reason."""
    mapping = {
        "stop": "end_turn",
        "length": "max_tokens",
        "tool_calls": "tool_use",
        "content_filter": "end_turn",
    }
    return mapping.get(finish_reason, "end_turn")


def translate_content_blocks(message: dict) -> list:
    """Convert OpenAI message content to Anthropic content blocks."""
    blocks = []

    # Tool calls → tool_use blocks
    if message.get("tool_calls"):
        for tc in message["tool_calls"]:
            func = tc.get("function", {})
            try:
                input_args = json.loads(func.get("arguments", "{}"))
            except json.JSONDecodeError:
                input_args = {}
            blocks.append({
                "type": "tool_use",
                "id": tc.get("id", f"toolu_{uuid.uuid4().hex[:24]}"),
                "name": func.get("name", "unknown"),
                "input": input_args,
            })

    # Text content
    content = message.get("content")
    if content:
        # If we have tool_calls, text goes first; otherwise it's the main content
        blocks.insert(0, {"type": "text", "text": content})
    elif not blocks:
        # No text and no tool calls — empty text block
        blocks.append({"type": "text", "text": ""})

    return blocks


def translate_response(openai_resp: dict, model: str = "claude-sonnet-4-20250514") -> dict:
    """Full OpenAI → Anthropic non-streaming response translation."""
    choice = openai_resp["choices"][0]
    message = choice["message"]

    return {
        "id": f"msg_{uuid.uuid4().hex[:24]}",
        "type": "message",
        "role": "assistant",
        "content": translate_content_blocks(message),
        "model": model,
        "stop_reason": map_stop_reason(choice.get("finish_reason")),
        "stop_sequence": None,
        "usage": {
            "input_tokens": openai_resp.get("usage", {}).get("prompt_tokens", 0),
            "output_tokens": openai_resp.get("usage", {}).get("completion_tokens", 0),
        },
    }


# ── Streaming: SSE Translation ───────────────────────────

class AnthropicSSEEncoder:
    """
    State machine that consumes OpenAI SSE chunks and emits Anthropic SSE events.

    Anthropic SSE event sequence:
      1. message_start  (contains message metadata)
      2. content_block_start  (one per content block)
      3. content_block_delta  (many per block)
      4. content_block_stop
      5. (repeat 2-4 for additional content blocks)
      6. message_delta  (stop_reason, usage)
      7. message_stop
    """

    def __init__(self, model: str = "claude-sonnet-4-20250514"):
        self.model = model
        self.msg_id = f"msg_{uuid.uuid4().hex[:24]}"
        self.started = False
        self.current_block_index = 0
        self.in_text_block = False
        self.in_tool_block = False
        self.current_tool_id = None
        self.current_tool_name = None
        self.tool_args_buffer = ""
        self.had_text_content = False
        self.output_tokens = 0
        self.finished = False

    def _event(self, event_type: str, data: dict) -> str:
        return f"event: {event_type}\ndata: {json.dumps(data)}\n\n"

    def start_message(self) -> str:
        self.started = True
        return self._event("message_start", {
            "type": "message_start",
            "message": {
                "id": self.msg_id,
                "type": "message",
                "role": "assistant",
                "content": [],
                "model": self.model,
                "stop_reason": None,
                "stop_sequence": None,
                "usage": {"input_tokens": 0, "output_tokens": 0},
            },
        })

    def start_text_block(self) -> str:
        self.in_text_block = True
        idx = self.current_block_index
        return self._event("content_block_start", {
            "type": "content_block_start",
            "index": idx,
            "content_block": {"type": "text", "text": ""},
        })

    def text_delta(self, text: str) -> str:
        if not self.in_text_block:
            # Auto-start text block
            prefix = self.start_text_block()
            return prefix + self._event("content_block_delta", {
                "type": "content_block_delta",
                "index": self.current_block_index,
                "delta": {"type": "text_delta", "text": text},
            })
        return self._event("content_block_delta", {
            "type": "content_block_delta",
            "index": self.current_block_index,
            "delta": {"type": "text_delta", "text": text},
        })

    def close_text_block(self) -> str:
        if not self.in_text_block:
            return ""
        self.in_text_block = False
        idx = self.current_block_index
        self.current_block_index += 1
        self.had_text_content = True
        return self._event("content_block_stop", {
            "type": "content_block_stop",
            "index": idx,
        })

    def start_tool_block(self, tool_id: str, tool_name: str) -> str:
        # Close any open text block first
        parts = self.close_text_block()
        self.in_tool_block = True
        self.current_tool_id = tool_id
        self.current_tool_name = tool_name
        self.tool_args_buffer = ""
        idx = self.current_block_index
        parts += self._event("content_block_start", {
            "type": "content_block_start",
            "index": idx,
            "content_block": {
                "type": "tool_use",
                "id": tool_id,
                "name": tool_name,
                "input": {},
            },
        })
        return parts

    def tool_args_delta(self, partial_json: str) -> str:
        self.tool_args_buffer += partial_json
        return self._event("content_block_delta", {
            "type": "content_block_delta",
            "index": self.current_block_index,
            "delta": {
                "type": "input_json_delta",
                "partial_json": partial_json,
            },
        })

    def close_tool_block(self) -> str:
        if not self.in_tool_block:
            return ""
        self.in_tool_block = False
        idx = self.current_block_index
        self.current_block_index += 1
        return self._event("content_block_stop", {
            "type": "content_block_stop",
            "index": idx,
        })

    def finish_message(self, stop_reason: str = "end_turn") -> str:
        parts = ""
        # Close any open blocks
        if self.in_text_block:
            parts += self.close_text_block()
        if self.in_tool_block:
            parts += self.close_tool_block()

        self.finished = True
        parts += self._event("message_delta", {
            "type": "message_delta",
            "delta": {
                "stop_reason": stop_reason,
                "stop_sequence": None,
            },
            "usage": {"output_tokens": self.output_tokens},
        })
        parts += self._event("message_stop", {
            "type": "message_stop",
        })
        return parts

    def process_chunk(self, chunk: dict) -> str:
        """Process an OpenAI SSE chunk and return Anthropic SSE events."""
        parts = ""

        if not self.started:
            parts += self.start_message()

        choices = chunk.get("choices", [])
        if not choices:
            return parts

        choice = choices[0]
        delta = choice.get("delta", {})
        finish_reason = choice.get("finish_reason")

        # Handle content (text)
        content = delta.get("content")
        if content:
            self.output_tokens += 1
            parts += self.text_delta(content)

        # Handle reasoning_content (thinking) — skip for now, CC may not need it
        # If CC requires thinking blocks, we'd add support here

        # Handle tool_calls
        tool_calls = delta.get("tool_calls")
        if tool_calls:
            for tc in tool_calls:
                tc_id = tc.get("id")
                func = tc.get("function", {})

                # New tool call — has id and name
                if tc_id and func.get("name"):
                    parts += self.start_tool_block(
                        tool_id=tc_id,
                        tool_name=func["name"],
                    )
                    if func.get("arguments"):
                        parts += self.tool_args_delta(func["arguments"])
                # Continuation of tool call — just arguments
                elif func.get("arguments"):
                    if not self.in_tool_block:
                        # Shouldn't happen, but handle gracefully
                        parts += self.start_tool_block(
                            tool_id=f"toolu_{uuid.uuid4().hex[:24]}",
                            tool_name="unknown",
                        )
                    parts += self.tool_args_delta(func["arguments"])

        # Finish
        if finish_reason:
            stop_reason = map_stop_reason(finish_reason)
            parts += self.finish_message(stop_reason)

        return parts


# ── HTTP Handler ─────────────────────────────────────────

async def handle_messages(request: web.Request) -> web.StreamResponse:
    """Handle POST /v1/messages — the main Anthropic API endpoint."""

    try:
        body = await request.json()
    except Exception as e:
        log.error(f"Failed to parse request body: {e}")
        return web.json_response(
            {"type": "error", "error": {"type": "invalid_request_error", "message": str(e)}},
            status=400,
        )

    is_stream = body.get("stream", False)
    requested_model = body.get("model", "claude-sonnet-4-20250514")
    log.info(f"Request: model={requested_model}, stream={is_stream}, "
             f"messages={len(body.get('messages', []))}, "
             f"tools={len(body.get('tools', []))}")

    # Translate request
    openai_body = translate_request(body)
    log.info(f"Translated: model={openai_body['model']}, "
             f"messages={len(openai_body['messages'])}, "
             f"stream={openai_body['stream']}")

    # Forward to qclaw
    headers = {
        "Authorization": QCLAW_AUTH,
        "Content-Type": "application/json",
    }

    if is_stream:
        return await handle_streaming(request, openai_body, headers, requested_model)
    else:
        return await handle_non_streaming(openai_body, headers, requested_model)


async def handle_non_streaming(openai_body: dict, headers: dict, model: str) -> web.Response:
    """Handle non-streaming request."""
    async with ClientSession() as session:
        async with session.post(QCLAW_URL, json=openai_body, headers=headers) as resp:
            if resp.status != 200:
                error_text = await resp.text()
                log.error(f"qclaw error {resp.status}: {error_text[:500]}")
                return web.json_response(
                    {"type": "error", "error": {"type": "api_error", "message": f"Upstream error {resp.status}: {error_text[:200]}"}},
                    status=resp.status,
                )
            openai_resp = await resp.json()
            anthropic_resp = translate_response(openai_resp, model=model)
            log.info(f"Response: stop_reason={anthropic_resp['stop_reason']}, "
                     f"blocks={len(anthropic_resp['content'])}")
            return web.json_response(anthropic_resp)


async def handle_streaming(request: web.Request, openai_body: dict, headers: dict, model: str) -> web.StreamResponse:
    """Handle streaming request with SSE translation."""
    response = web.StreamResponse(
        status=200,
        headers={
            "Content-Type": "text/event-stream",
            "Cache-Control": "no-cache",
            "Connection": "keep-alive",
        },
    )
    await response.prepare(request)

    encoder = AnthropicSSEEncoder(model=model)

    async with ClientSession() as session:
        async with session.post(QCLAW_URL, json=openai_body, headers=headers) as resp:
            if resp.status != 200:
                error_text = await resp.text()
                log.error(f"qclaw stream error {resp.status}: {error_text[:500]}")
                # Send error as Anthropic-style SSE
                error_event = encoder._event("error", {
                    "type": "error",
                    "error": {"type": "api_error", "message": f"Upstream error {resp.status}"},
                })
                await response.write(error_event.encode())
                await response.write_eof()
                return response

            buffer = ""
            async for raw_chunk in resp.content:
                buffer += raw_chunk.decode("utf-8", errors="replace")

                # Process complete SSE lines
                while "\n" in buffer:
                    line, buffer = buffer.split("\n", 1)
                    line = line.strip()

                    if not line:
                        continue

                    if line == "data: [DONE]":
                        # End of stream — ensure we've sent finish
                        if not encoder.finished:
                            finish = encoder.finish_message("end_turn")
                            await response.write(finish.encode())
                        await response.write(b"event: done\ndata: {}\n\n")
                        continue

                    if line.startswith("data: "):
                        data_str = line[6:]
                        try:
                            chunk = json.loads(data_str)
                        except json.JSONDecodeError:
                            log.warning(f"Failed to parse SSE chunk: {data_str[:100]}")
                            continue

                        # Check for error in chunk
                        if chunk.get("error"):
                            log.error(f"Error in chunk: {chunk['error']}")
                            continue

                        # Translate chunk
                        events = encoder.process_chunk(chunk)
                        if events:
                            await response.write(events.encode())

            # Process any remaining buffer
            if buffer.strip():
                line = buffer.strip()
                if line.startswith("data: ") and line != "data: [DONE]":
                    data_str = line[6:]
                    try:
                        chunk = json.loads(data_str)
                        events = encoder.process_chunk(chunk)
                        if events:
                            await response.write(events.encode())
                    except json.JSONDecodeError:
                        pass

            # Final safety: ensure message is closed
            if not encoder.finished:
                finish = encoder.finish_message("end_turn")
                await response.write(finish.encode())

    await response.write_eof()
    return response


# ── Passthrough / Health Endpoints ──────────────────────

async def handle_health(request: web.Request) -> web.Response:
    """Health check endpoint."""
    return web.json_response({"status": "ok", "service": "anthropic-qclaw-proxy"})


async def handle_models(request: web.Request) -> web.Response:
    """Fake /v1/models endpoint that CC might query."""
    return web.json_response({
        "object": "list",
        "data": [
            {
                "id": "claude-sonnet-4-20250514",
                "object": "model",
                "created": 1700000000,
                "owned_by": "anthropic",
            },
        ],
    })


async def handle_authorize(request: web.Request) -> web.Response:
    """Fake authorize endpoint — CC interactive mode checks this before using the proxy."""
    return web.json_response({"status": "ok"})


# ── Main ────────────────────────────────────────────────

def main():
    parser = argparse.ArgumentParser(description="Anthropic-to-OpenAI Translation Proxy for CC + qclaw")
    parser.add_argument("--port", type=int, default=20000, help="Listen port (default: 20000)")
    parser.add_argument("--qclaw-url", type=str, default=QCLAW_URL, help="qclaw gateway URL")
    parser.add_argument("--debug", action="store_true", help="Enable debug logging")
    args = parser.parse_args()

    if args.debug:
        logging.getLogger("anthropic-proxy").setLevel(logging.DEBUG)

    qclaw_url = args.qclaw_url

    app = web.Application()
    app.router.add_post("/v1/messages", handle_messages)
    app.router.add_get("/v1/models", handle_models)
    app.router.add_get("/health", handle_health)
    
    # Fake authorize endpoint for CC interactive mode
    app.router.add_get("/v1/authorize", handle_authorize)
    app.router.add_post("/v1/authorize", handle_authorize)

    log.info(f"Starting Anthropic→qclaw proxy on port {args.port}")
    log.info(f"  qclaw gateway: {qclaw_url}")
    log.info(f"  Set for CC: ANTHROPIC_BASE_URL=http://127.0.0.1:{args.port}")
    log.info(f"  Set for CC: ANTHROPIC_API_KEY=qclaw-proxy")

    web.run_app(app, host="127.0.0.1", port=args.port, print=None)


if __name__ == "__main__":
    main()
