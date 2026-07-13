import { adminApi, type FeishuCardPreset } from './admin'

const loadPresets: () => Promise<FeishuCardPreset[]> = adminApi.debugFeishuCardPresets

void loadPresets
