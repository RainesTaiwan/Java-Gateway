import { Agent } from "@cursor/sdk";

function readPrompt() {
  const encoded = process.env.AGENT_PROMPT_B64;
  if (encoded && encoded.trim()) {
    return Buffer.from(encoded, "base64").toString("utf8");
  }
  return process.env.AGENT_PROMPT ?? "";
}

const prompt = readPrompt();
const apiKey = process.env.CURSOR_API_KEY ?? "";
const modelId = process.env.CURSOR_MODEL ?? "composer-2.5";

if (!prompt.trim()) {
  console.error("AGENT_PROMPT is empty");
  process.exit(1);
}
if (!apiKey.trim()) {
  console.error("CURSOR_API_KEY is not configured");
  process.exit(1);
}

try {
  const result = await Agent.prompt(prompt, {
    apiKey,
    model: { id: modelId },
    local: { cwd: "/app" },
  });

  if (result.status === "finished") {
    if (result.result) {
      console.log(result.result);
    }
    process.exit(0);
  }

  console.error(`Cursor agent finished with status=${result.status}`);
  process.exit(2);
} catch (error) {
  const message = error instanceof Error ? error.message : String(error);
  console.error("Cursor agent execution failed:", message);
  process.exit(1);
}
