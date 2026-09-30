from __future__ import annotations

import asyncio
import base64
import tempfile
import unittest
import uuid

from heartnote_capture.mcp_server import build_server
from heartnote_capture.store import CaptureStore, minimal_png


class MCPServerTest(unittest.TestCase):
    def test_only_read_tools_and_ai_filter(self) -> None:
        try:
            from mcp import Client
        except ImportError:
            self.skipTest("MCP extra is not installed")
        with tempfile.TemporaryDirectory() as directory:
            store = CaptureStore(directory)
            png = base64.b64encode(minimal_png()).decode("ascii")
            for capture_id, access in (
                ("capture-mcp-allowed", "remote_no_memory"),
                ("capture-mcp-denied", "deny"),
            ):
                store.put(
                    capture_id,
                    {
                        "id": capture_id,
                        "kind": "thought",
                        "comment": "MCP 可读测试",
                        "source": {"text": "证据"},
                        "ai_access": access,
                        "assets": {
                            "original": {"content_type": "image/png", "data_base64": png},
                            "context": {"content_type": "image/png", "data_base64": png},
                        },
                        "evidence": {"context": {"image": {"retained": True,"width":1,"height":1,
                            "selection":{"left":0,"top":0,"right":1,"bottom":1}},
                            "text":{"full_text":"更长的原文证据","origin":"user_supplied"}}},
                    },
                )
            server = build_server(store)
            chat_id = str(uuid.uuid4())
            stamp = "2026-09-29T00:00:00Z"
            private_chat = "PRIVATE_CHAT_NOT_IN_MCP"
            store.chats.put(chat_id, {
                "schema_version": 1, "id": chat_id, "record_id": "capture-mcp-allowed",
                "title": private_chat, "created_at": stamp, "updated_at": stamp,
                "snapshot": {"record_id": "capture-mcp-allowed", "modules": ["thought"],
                             "thought": private_chat, "consent": "record_chat_only"},
                "model": {"label": "Synthetic", "model": "synthetic", "base_url": "https://example.invalid/v1"},
                "messages": [{"id": str(uuid.uuid4()), "role": "user", "content": private_chat,
                              "created_at": stamp, "status": "complete", "request_id": str(uuid.uuid4()), "error": ""}],
            }, 0)

            async def check() -> None:
                async with Client(server) as client:
                    tools = await client.list_tools()
                    names = {tool.name for tool in tools.tools}
                    self.assertEqual(
                        {"search_captures", "get_capture", "list_recent", "list_todos", "read_timeline"},
                        names,
                    )
                    self.assertTrue(all(tool.annotations.read_only_hint for tool in tools.tools))
                    result = await client.call_tool("search_captures", {"query": "MCP"})
                    text = str(result.structured_content)
                    self.assertIn("capture-mcp-allowed", text)
                    self.assertNotIn("capture-mcp-denied", text)
                    self.assertNotIn(private_chat, text)
                    self.assertNotIn(chat_id, text)
                    capture = await client.call_tool(
                        "get_capture", {"capture_id": "capture-mcp-allowed"}
                    )
                    self.assertEqual("capture-mcp-allowed", capture.structured_content["id"])
                    self.assertNotIn(private_chat, str(capture.structured_content))
                    self.assertNotIn(chat_id, str(capture.structured_content))
                    self.assertIn("image", {block.type for block in capture.content})
                    self.assertEqual(2, sum(block.type == "image" for block in capture.content))
                    labels = [block.text for block in capture.content if block.type == "text"]
                    self.assertTrue(labels[1].startswith("Image role: context"))
                    self.assertTrue(labels[2].startswith("Image role: original"))
                    self.assertEqual("更长的原文证据",capture.structured_content["evidence"]["context"]["text"]["full_text"])
                    denied = await client.call_tool("get_capture",{"capture_id":"capture-mcp-denied"})
                    self.assertFalse(any(block.type == "image" for block in denied.content))

            asyncio.run(check())


if __name__ == "__main__":
    unittest.main()
