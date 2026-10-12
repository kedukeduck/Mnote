import { transcriptProxy } from "../../../../lib/transcript-proxy";

export const dynamic = "force-dynamic";

async function handle(request: Request, context: { params: Promise<{ path: string[] }> }) {
  const { path } = await context.params;
  return transcriptProxy(request, path);
}

export const GET = handle;
export const POST = handle;
export const DELETE = handle;
