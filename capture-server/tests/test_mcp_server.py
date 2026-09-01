from __future__ import annotations

import asyncio
import base64
import tempfile
import unittest

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
                            "original": {"content_type": "image/png", "data_base64": png}
                        },
                    },
                )
            server = build_server(store)

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
                    capture = await client.call_tool(
                        "get_capture", {"capture_id": "capture-mcp-allowed"}
                    )
                    self.assertEqual("capture-mcp-allowed", capture.structured_content["id"])
                    self.assertIn("image", {block.type for block in capture.content})

            asyncio.run(check())


if __name__ == "__main__":
    unittest.main()
