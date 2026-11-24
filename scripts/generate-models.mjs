#!/usr/bin/env node
import { writeFile } from 'node:fs/promises';

const providers = [
  ['openai', 'openai', 'OpenAI', 'https://api.openai.com/v1', 'openai-responses'],
  ['anthropic', 'anthropic', 'Anthropic', 'https://api.anthropic.com', 'anthropic-messages'],
  ['google', 'google', 'Google', 'https://generativelanguage.googleapis.com/v1beta', 'google-generative-ai'],
  ['deepseek', 'deepseek', 'DeepSeek', 'https://api.deepseek.com', 'openai-completions'],
  ['openrouter', 'openrouter', 'OpenRouter', 'https://openrouter.ai/api/v1', 'openai-completions'],
  ['xai', 'xai', 'xAI', 'https://api.x.ai/v1', 'openai-responses'],
  ['groq', 'groq', 'Groq', 'https://api.groq.com/openai/v1', 'openai-completions'],
  ['mistral', 'mistral', 'Mistral', 'https://api.mistral.ai/v1', 'openai-completions'],
  ['moonshotai', 'moonshotai', 'Moonshot AI', 'https://api.moonshot.ai/v1', 'openai-completions'],
  ['moonshotai-cn', 'moonshotai-cn', 'Moonshot AI China', 'https://api.moonshot.cn/v1', 'openai-completions'],
  ['zai', 'zai', 'Z.AI', 'https://api.z.ai/api/coding/paas/v4', 'openai-completions'],
  ['minimax', 'minimax', 'MiniMax', 'https://api.minimax.io/anthropic', 'anthropic-messages'],
  ['minimax-cn', 'minimax-cn', 'MiniMax China', 'https://api.minimaxi.com/anthropic', 'anthropic-messages'],
  ['qwen-token-plan', 'alibaba-token-plan', 'Qwen', 'https://token-plan.ap-southeast-1.maas.aliyuncs.com/compatible-mode/v1', 'openai-completions'],
  ['qwen-token-plan-cn', 'alibaba-token-plan-cn', 'Qwen China', 'https://token-plan.cn-beijing.maas.aliyuncs.com/compatible-mode/v1', 'openai-completions'],
  ['qwen-token-plan-individual', 'alibaba-token-plan', 'Qwen Individual', 'https://token-plan.ap-southeast-1.maas.aliyuncs.com/compatible-mode/v1', 'openai-completions'],
  ['xiaomi', 'xiaomi', 'Xiaomi', 'https://api.xiaomimimo.com/v1', 'openai-completions'],
  ['xiaomi-token-plan-cn', 'xiaomi-token-plan-cn', 'Xiaomi China', 'https://token-plan-cn.xiaomimimo.com/v1', 'openai-completions'],
  ['xiaomi-token-plan-ams', 'xiaomi-token-plan-ams', 'Xiaomi Amsterdam', 'https://token-plan-ams.xiaomimimo.com/v1', 'openai-completions'],
  ['xiaomi-token-plan-sgp', 'xiaomi-token-plan-sgp', 'Xiaomi Singapore', 'https://token-plan-sgp.xiaomimimo.com/v1', 'openai-completions'],
  ['together', 'togetherai', 'Together', 'https://api.together.ai/v1', 'openai-completions'],
  ['fireworks', 'fireworks-ai', 'Fireworks', 'https://api.fireworks.ai/inference/v1', 'openai-completions'],
  ['cerebras', 'cerebras', 'Cerebras', 'https://api.cerebras.ai/v1', 'openai-completions'],
  ['huggingface', 'huggingface', 'Hugging Face', 'https://router.huggingface.co/v1', 'openai-completions'],
];

const response = await fetch('https://models.dev/api.json');
if (!response.ok) throw new Error(`models.dev: HTTP ${response.status}`);
const source = await response.json();
const individual = new Set(['deepseek-v4-flash-0731', 'deepseek-v4-pro', 'deepseek-v4-pro-0813',
  'glm-5.2', 'qwen3.6-flash', 'qwen3.7-max', 'qwen3.7-plus', 'qwen3.8-flash', 'qwen3.8-max']);
const implemented = new Set(['openai-completions', 'openai-responses', 'anthropic-messages', 'google-generative-ai']);
const result = providers.filter(([id, , , , api]) => implemented.has(api)
  && (api !== 'anthropic-messages' || id === 'anthropic'))
  .map(([id, sourceId, name, baseUrl, api]) => {
  const entry = source[sourceId];
  if (!entry) throw new Error(`Missing provider ${sourceId}`);
  const models = Object.values(entry.models).filter(m => m.tool_call && m.limit?.context && m.limit?.output
    && !/\bcodex\b/i.test(m.id) && (m.modalities?.output ?? ['text']).includes('text') && m.status !== 'deprecated'
    && !(id.startsWith('qwen-token-plan') && m.id === 'qwen3.8-max-preview')
    && (id !== 'qwen-token-plan-individual' || individual.has(m.id)))
    .map(m => ({ id: m.id, name: m.name || m.id, input: m.modalities?.input?.includes('image')
      ? ['text', 'image'] : ['text'], reasoning: m.reasoning === true,
      contextWindow: m.limit.context, maxTokens: m.limit.output,
      cost: { input: m.cost?.input ?? 0, output: m.cost?.output ?? 0,
        cacheRead: m.cost?.cache_read ?? 0, cacheWrite: m.cost?.cache_write ?? 0 },
      headers: m.headers ?? {} }))
    .sort((a, b) => a.id.localeCompare(b.id));
  return { id, name, baseUrl, api, models };
});
await writeFile('core/src/main/resources/io/github/izakyl/jpi/ai/models.json', JSON.stringify(result, null, 2) + '\n');
console.log(result.map(p => `${p.id}: ${p.models.length}`).join('\n'));
