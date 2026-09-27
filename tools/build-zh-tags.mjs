// 生成 data/prompt-zh-tags.json：内置"中文 ↔ SD 标准词条"对照词库。
//
// 数据来源（按优先级从高到低）：
//   1. data/prompt-zh-extra.txt  手工同义词表（中文=tag1,tag2，左侧可用 / 分隔多个同义词）
//   2. data/prompt-usage.json    本机 Prompt All-in-One 中文分类词库（人工分类、机翻含义）
//   3. danbooru.zh_CN_SFW.csv    a1111-sd-webui-tagcomplete 的中文翻译（覆盖广、翻译较粗糙）
// 所有词条都必须出现在 data/prompt-tags.txt 标准词库里，否则丢弃并计数。
//
// 用法：node tools/build-zh-tags.mjs [--tags-dir <目录>] [--check]
//   --tags-dir 指向含 danbooru.csv / danbooru.zh_CN_SFW.csv 的目录；
//              默认用随仓库分发的 data/danbooru（上游原始词表），
//              也可以指到 a1111-sd-webui-tagcomplete 的 tags 目录。
//   --check    只校验现有 data/prompt-zh-tags.json 是否与来源一致，不写文件
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';

const REPO = path.resolve(path.dirname(new URL(import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1')), '..');
const argv = process.argv.slice(2);
const flag = (name, fallback) => {
  const at = argv.indexOf(name);
  return at >= 0 && argv[at + 1] ? argv[at + 1] : fallback;
};
const checkOnly = argv.includes('--check');
// 默认用随仓库分发的上游词表（data/danbooru），装没装 SD WebUI 扩展都能重建。
const TAGS_DIR = path.resolve(REPO, flag('--tags-dir', 'data/danbooru'));

const sha256 = (file) => crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex');
/**
 * 写进 sources 里的来源路径：仓库内的写成仓库相对路径（data/danbooru/xxx.csv），
 * 免得把本机绝对路径（F:\sd\...）写进公开的词库文件里；仓库外才写绝对路径。
 */
const sourcePath = (file) => {
  const rel = path.relative(REPO, path.resolve(file));
  return rel && !rel.startsWith('..') ? rel.replace(/\\/g, '/') : path.resolve(file).replace(/\\/g, '/');
};
const readLines = (file) => fs.readFileSync(file, 'utf8').split(/\r?\n/);

/** 标准词库：小写写法与下划线/空格两种形态都接受。 */
const dictionary = new Map();   // 归一化写法 -> 词库里真实的写法
let dictionaryTags = 0;
for (const line of readLines(path.join(REPO, 'data/prompt-tags.txt'))) {
  const tag = line.trim();
  if (!tag || tag.startsWith('#')) continue;
  dictionaryTags++;
  for (const form of [tag.toLowerCase(), tag.toLowerCase().replace(/_/g, ' ')]) {
    if (!dictionary.has(form)) dictionary.set(form, tag.toLowerCase());
  }
}

const CATEGORIES = ['人物', '角色', '作品', '表情', '动作', '姿势', '服装', '场景', '环境', '物品', '镜头', '画面', '其他'];
const CATEGORY_INDEX = new Map(CATEGORIES.map((name, index) => [name, index]));

const PATTERNS = [
  [/^\d+\+?(girl|girls|boy|boys|other|others|people|person)$/, '人物'],
  [/(expression|smile|smirk|grin$|grinning|crying|tears|teary|blush|angry|anger|annoyed|surprised|embarrass|frown|pout|scowl|grimace|open_mouth|closed_mouth|closed_eyes|one_eye_closed|wink|half-closed|happy|sad$|sadness|disgust|fear|scared|shocked|nervous|serious|thinking|sleepy|yawn|tongue_out|sweatdrop|emoji|drooling|gritted_teeth|sigh|screaming|shouting|smug|confused|worried|pensive|determined|expressionless|empty_eyes|dead_eyes|glaring|staring|eye_contact|teasing)/, '表情'],
  [/(pose|sitting|seiza|wariza|kneeling|squatting|crouching|lying|prone|supine|on_back|on_stomach|on_side|all_fours|legs_up|crossed_legs|hands_on_hips|arms_behind_back|straddling|leaning|bent_over|upside_down|handstand|standing|t-pose|a-pose|spread_legs|arms_up|arms_crossed|hands_behind_head|leg_lock|legs_apart|knees_up|knees_together|feet_up|head_tilt|bowl_pose|hugging_own_legs|fetal_position|yoga|stretching_arms)/, '姿势'],
  [/(holding|grabbing|touching|caress|fondl|petting|patting|stroking|looking_at|looking_back|looking_down|looking_up|looking_away|looking_through|eating|drinking|sleeping|running|walking|jumping|dancing|singing|kissing|hug|carrying|playing|reading|writing|drawing|cooking|swimming|fighting|pointing|lifting|pulling|pushing|throwing|reaching|pressing|bathing|undressing|dressing|riding|waving|clapping|yawning|biting|licking|sucking|pinching|wiping|covering|embracing|climbing|crawling|feeding|shopping|working|studying|training|praying|meditating|saluting|presenting|offering|receiving|adjusting|removing|putting_on|taking_off|opening|closing|typing|texting|calling|photographing|filming|juggling|jumping_rope|weightlifting)/, '动作'],
  [/(shirt|skirt|dress|uniform|serafuku|sailor|suit|blazer|jacket|coat|sweater|hoodie|sweatshirt|vest|apron|maid|nurse|kimono|yukata|china_dress|qipao|hanbok|wedding|swimsuit|bikini|lingerie|underwear|bra$|panties|panty|boxers|briefs|thighhigh|pantyhose|stockings|socks|legwear|boots|shoes|sneakers|sandals|heels|footwear|hat$|hats|cap$|beret|helmet|hood|scarf|gloves|mittens|cape|cloak|armor|armour|necklace|earrings?$|bracelet|ring$|watch$|glasses|sunglasses|eyewear|goggles|mask|blindfold|ribbon|hairband|hair_bow|hair_ornament|hairpin|hairclip|headdress|tiara|crown|hair_flower|flower_hair|hair_accessory|belt|suspenders|necktie|bowtie|backpack|handbag|purse|briefcase|umbrella|loafers|jewel|choker|capelet|poncho|cardigan|tank_top|halter|bodysuit|leotard|gym_uniform|track_suit|jersey|sarong|shawl|veil|outfit|costume|clothes|clothing|footwear|mitten|garter|leggings|sweater_vest|hair_ribbon)/, '服装'],
  [/(^|_)(shot|close-up|closeup|portrait|pov|from_above|from_below|from_behind|from_side|from_outside|full_body|upper_body|lower_body|cowboy_shot|wide_shot|medium_shot|depth_of_field|blurry|blur|bokeh|wide-angle|fisheye|silhouette|selfie|zoomed|out_of_frame|off-screen|composition|perspective|viewfinder|angle|framing|cropped|head_out_of_frame|character_focus|looking_at_viewer)/, '镜头'],
  [/(masterpiece|best_quality|highres|absurdres|high_resolution|ultra-detailed|quality|lighting|backlighting|rim_light|god_rays|sunbeam|light_rays|gradient|monochrome|greyscale|sepia|vibrant|saturat|pastel|colou?r|watercolor|oil_painting|sketch|lineart|line_art|cel_shading|flat_color|pixel_art|retro|vintage|cyberpunk|steampunk|ukiyo-e|impressionism|realistic|photorealistic|3d|anime_style|art_style|aesthetic|glitch|chromatic_aberration|motion_blur|chromatic)/, '画面'],
  [/(sky|cloud|sun$|sunset|sunrise|dawn|dusk|twilight|night|midnight|evening|morning|noon|afternoon|moon|moonlight|starlight|star$|stars$|galaxy|aurora|rain|snow|storm|thunder|lightning|fog|mist|haze|wind|typhoon|tornado|rainbow|weather|season|spring|summer|autumn|winter|cherry_blossom|sakura|maple|leaf|leaves|forest|woods|bamboo|tree|grass|meadow|flower|desert|mountain|hill|cliff|cave|beach|shore|coast|ocean|sea$|underwater|wave|lake|river|waterfall|stream|swamp|volcano|glacier|snowfield|island|cloudy|overcast|sunny|clear_sky|outdoors|nature|landscape|scenery|horizon|tide|steam|smoke|dust|fire|bonfire|explosion|sparkle|night_sky|milky_way|skyline|sunlight|rainbow|dawn|field$|fields$|garden|water)/, '环境'],
  [/(background|indoors|indoor|room$|classroom|school|office|hospital|library|kitchen|bathroom|bedroom|dining|restaurant|cafe|café|bar$|bars$|shop|store|market|station|street|road|alley|bridge|tunnel|park$|playground|shrine|temple|church|castle|palace|ruins|construction|cemetery|graveyard|village|town|city|building|tower|rooftop|balcony|corridor|hallway|stairs|elevator|stage|stadium|gym|pool|onsen|hot_spring|hotel|inn$|lobby|convenience_store|supermarket|bookstore|warehouse|basement|attic|prison|dungeon|spaceship|aircraft|train_interior|car_interior|vehicle_interior|deck|harbor|port$|airport|factory|laboratory|clinic|infirmary|nursery|kindergarten|dormitory|locker_room|changing_room|fitting_room|shower|toilet|bathtub|sauna|izakaya|food_stand|street_stall|counter|checkout|reception|throne_room|banquet|ballroom|auditorium|amusement_park|ferris_wheel|zoo|aquarium|museum|art_gallery|plaza|square$|courtyard|farm|orchard|greenhouse|window|door|wall$|floor|ceiling|mirror|furniture)/, '场景'],
  [/(book|notebook|pencil|pen$|eraser|ruler|blackboard|chalk|desk|chair|table|computer|keyboard|mouse|screen|lamp|document|newspaper|map$|telescope|phone|camera|headphone|microphone|speaker|television|fan$|fridge|kettle|cup$|mug|glass$|bottle|wine_glass|chopsticks|fork|spoon|plate|bowl|pot$|pan$|umbrella|key$|keys$|wallet|bag$|suitcase|luggage|clock|alarm|comb|towel|soap|toothbrush|tissue|candle|lantern|rope|chain|ladder|broom|bucket|balloon|food|bread|cake|cookie|donut|sandwich|burger|fries|pizza|sushi|rice|noodle|ramen|ice_cream|chocolate|candy|apple|banana|strawberry|watermelon|orange$|lemon|grape|cherry|peach|vegetable|tomato|carrot|corn|mushroom|egg$|steak|meat|chicken|fish|shrimp|coffee|tea$|juice|soda|beer|wine|milk|bento|dessert|bed$|sofa|couch|cabinet|bookshelf|wardrobe|drawer|carpet|curtain|blanket|pillow|mirror|picture_frame|poster|vase|potted_plant|fireplace|box$|crate|trash|guitar|violin|cello|piano|drum|flute|trumpet|saxophone|ball$|soccer|basketball|baseball|volleyball|tennis|racket|dumbbell|skateboard|bicycle|sword|katana|dagger|spear|axe|hammer|bow$|arrow|gun|pistol|rifle|shield|staff|wand|grimoire|potion|crystal_ball|scroll|playing_card|dice|amulet|weapon|tool|instrument)/, '物品'],
  [/(^|_)(girl|girls|boy|boys|woman|women|man$|men$|child|children|kid|lady|lord|person|people|student|teacher|doctor|nurse|police|officer|soldier|knight|princess|prince|queen|king|maid|butler|miko|nun|priest|witch|wizard|vampire|angel|demon|elf|orc|robot|android|scientist|engineer|merchant|farmer|chef|cook|clerk|idol|singer|dancer|artist|writer|photographer|model|athlete|swordsman|samurai|ninja|pirate|cowboy|hunter|thief|detective|reporter|driver|astronaut|pilot|sailor|monk|nekomimi|catgirl|foxgirl|bunny_girl|mermaid|fairy|ghost|zombie|skeleton|monster|twin|sister|brother|mother|father|family|friend|couple|classmate|crowd|audience|fan$|hair|eyes|eye$|face|ears|ear$|breasts|breast|skin|navel|teeth|tongue|mouth|lips|nose|cheek|thigh|legs|leg$|arms|arm$|hands|hand$|fingers|finger|feet|foot|nails|neck|shoulders|back$|waist|hips|abs|muscular|muscle)/, '人物'],
];

const CHINESE_PATTERNS = [
  [/(笑|哭|泪|怒|生气|惊讶|震惊|羞|脸红|皱|瞪|眯|张嘴|闭嘴|咬牙|吐舌|舔|嘟嘴|表情|眼神|害怕|恐惧|不安|厌恶|嫌弃|得意|尴尬|紧张|难过|悲伤|开心|高兴|困惑|疑惑|好奇|疲惫|困倦|无奈|叹气|吃惊|媚|挑逗|无表情)/, '表情'],
  [/(站立|坐着|跪着|躺着|趴着|蹲着|盘腿|蜷缩|跷|张腿|抬腿|弯腰|俯身|后仰|前倾|倒立|悬空|靠在|跨坐|姿势|双臂|叉腰|抱胸|合十|托腮|单腿|侧身|仰卧|俯卧|侧卧|二郎腿|跪坐|侧坐|坐姿|站姿)/, '姿势'],
  [/(走|跑|跳|爬|游泳|潜水|骑|驾驶|飞行|跳舞|唱歌|演奏|弹|拉动|吹奏|打击|踢|挥|射击|投掷|抓住|握住|拿着|举起|抱起|扔|推开|抚摸|亲吻|拥抱|牵手|阅读|书写|绘画|吃|喝水|睡觉|拍照|自拍|打电话|购物|结账|做饭|煮|洗涤|打扫|浇花|种植|喂食|等车|上车|下车|开门|关门|按下|偷看|注视|对视|回头|转身|伸懒腰|打哈欠|抚摸|触碰|按压|袭击|战斗|拥抱|背着|举起)/, '动作'],
  [/(校服|水手服|西装|衬衫|T恤|毛衣|卫衣|外套|夹克|大衣|风衣|羽绒服|连衣裙|长裙|短裙|百褶裙|紧身裙|牛仔裤|短裤|运动裤|围裙|女仆装|护士服|白大褂|巫女服|和服|浴衣|旗袍|婚纱|礼服|泳装|学校泳装|比基尼|内衣|胸罩|内裤|丝袜|连裤袜|长筒袜|短袜|过膝袜|条纹袜|靴子|高跟鞋|运动鞋|凉鞋|拖鞋|皮鞋|帽子|贝雷帽|鸭舌帽|毛线帽|草帽|头盔|发箍|发卡|发带|蝴蝶结|丝带|项链|耳环|戒指|手镯|手表|眼镜|太阳镜|面具|围巾|手套|披风|斗篷|盔甲|铠甲|腰带|背包|书包|挎包|服装|穿搭|衣着)/, '服装'],
  [/(教室|学校|校园|医院|病房|图书馆|办公室|会议室|便利店|超市|书店|咖啡厅|咖啡馆|餐厅|饭馆|酒吧|居酒屋|厨房|卧室|客厅|浴室|淋浴间|更衣室|泳池|游泳池|健身房|体育馆|舞蹈室|道场|仓库|地下室|电梯|楼梯|天台|屋顶|阳台|宿舍|旅馆|酒店|大厅|柜台|收银台|车站|月台|车厢|机舱|机场|公园|游乐园|操场|广场|庭院|花园|神社|寺庙|教堂|城堡|宫殿|废墟|工地|墓地|村庄|城镇|城市|街道|小巷|胡同|夜市|集市|祭典|舞台|会场|室内|室外|背景|店内|店里|房间|走廊|校门|门口|桥上|隧道)/, '场景'],
  [/(晴天|阴天|多云|下雨|暴雨|雷雨|闪电|打雷|下雪|暴雪|冰雹|雾|浓雾|霾|大风|台风|龙卷风|彩虹|极光|日出|日落|黄昏|清晨|正午|傍晚|夜晚|深夜|满月|新月|月光|阳光|夕阳|朝霞|晚霞|天空|云|星空|银河|森林|树林|竹林|草原|田野|花田|沙漠|雪山|山顶|悬崖|洞穴|海滩|海岸|海边|沙滩|海岛|湖|河|瀑布|溪流|温泉|沼泽|火山|冰川|海底|太空|宇宙|自然|风景|天气|季节|春天|夏天|秋天|冬天|樱花|红叶|落叶|新绿|向日葵|玫瑰|郁金香|蒲公英|荷花|梅花|藤蔓|草地|杂草|苔藓|枯树|松树|柳树|枫树|椰子树)/, '环境'],
  [/(书本|笔记|铅笔|钢笔|橡皮|尺子|书包|黑板|粉笔|课桌|椅子|桌子|电脑|键盘|鼠标|屏幕|台灯|文件|报纸|地图|望远镜|手机|相机|耳机|麦克风|音箱|电视|电风扇|空调|冰箱|微波炉|水壶|茶杯|咖啡杯|马克杯|玻璃杯|酒瓶|酒杯|餐具|筷子|刀叉|勺子|盘子|碗|锅|平底锅|锅铲|雨伞|钥匙|钱包|手提包|行李箱|背包|手表|闹钟|镜子|梳子|毛巾|肥皂|牙刷|纸巾|蜡烛|灯笼|绳子|锁链|梯子|扫帚|水桶|气球|米饭|面条|面包|蛋糕|饼干|甜甜圈|三明治|汉堡|薯条|披萨|寿司|饭团|拉面|冰淇淋|巧克力|糖果|水果|苹果|香蕉|草莓|西瓜|橘子|柠檬|葡萄|樱桃|桃子|蔬菜|番茄|胡萝卜|玉米|蘑菇|鸡蛋|牛排|烤肉|炸鸡|鱼|虾|咖啡|茶|奶茶|果汁|汽水|啤酒|红酒|牛奶|便当|甜点|家具|床|沙发|柜子|书架|衣柜|抽屉|地毯|窗帘|被子|枕头|毯子|抱枕|床单|画框|海报|花瓶|盆栽|时钟|壁炉|吊灯|箱子|纸箱|垃圾箱|吉他|电吉他|贝斯|小提琴|大提琴|钢琴|鼓|长笛|小号|萨克斯|球|足球|篮球|棒球|排球|网球拍|羽毛球|乒乓球|哑铃|跳绳|滑板|轮滑|自行车|游泳圈|风筝|剑|刀|武士刀|匕首|长矛|斧头|锤子|弓|箭|弩|枪|手枪|步枪|盾|盔甲|头盔|法杖|魔法书|水晶球|药水|卷轴|扑克牌|骰子|面具|护身符|道具|物品)/, '物品'],
];

const classify = (tag, danbooruCategory, aliases) => {
  if (danbooruCategory === 4) return '角色';
  if (danbooruCategory === 3) return '作品';
  for (const [pattern, name] of PATTERNS) if (pattern.test(tag)) return name;
  const joined = aliases.join('|');
  for (const [pattern, name] of CHINESE_PATTERNS) if (pattern.test(joined)) return name;
  return '其他';
};

const CJK = /[\u3400-\u9fff\uf900-\ufaff]/;
const cleanAlias = (text) => {
  const value = text.replace(/[\u3000\s]+/g, '').replace(/[，,。.;；:：!！?？"'“”‘’()（）\[\]【】<>《》]/g, '');
  return value;
};
/**
 * 机械清洗社区机翻的中文写法：整词重复（"广告广告"）、乱码/控制字符、混进拉丁字母
 * （"黄昏hack"、"fgo" 这类既不是中文说法，又会和别的词条抢匹配）都直接丢掉。
 */
const tidyAlias = (word) => {
  if (!word) return '';
  if (/[\p{Cc}\p{Cf}\uFFFD\uE000-\uF8FF]/.test(word)) return '';
  if (/[A-Za-z]/.test(word)) return '';
  // 整词就是同一小段重复（广告广告、飞艇飞艇、2个女孩2个女孩）→ 只留一段
  for (let size = 1; size <= word.length / 2; size++) {
    if (word.length % size !== 0) continue;
    const piece = word.slice(0, size);
    if (piece.repeat(word.length / size) === word) return piece;
  }
  return word;
};
/** 从"pov 目光接触"这类混合含义里取出中文词：按分隔符切开，只保留含汉字的片段。 */
const aliasesOf = (meaning) => {
  const out = new Set();
  for (const raw of meaning.split(/[\s,;、\/|]+/)) {
    const token = tidyAlias(cleanAlias(raw));
    if (!token || !CJK.test(token)) continue;
    if (token.length > 12) continue;
    out.add(token);
  }
  return [...out];
};

/** tag -> { aliases: Map<中文, 优先级>, category, rank }；优先级 0 最高。 */
const entries = new Map();
const stats = { unknownTags: 0, unknownSamples: [], extraLines: 0, usageTags: 0, csvTags: 0 };
const addEntry = (rawTag, aliases, priority, danbooruCategory) => {
  const key = rawTag.trim().toLowerCase();
  if (!key) return false;
  // 带括号或冒号的词条（角色名、作品名、特殊语法）对画面没有直接帮助，机器人也会过滤，不进中文词库。
  if (key.indexOf('(') >= 0 || key.indexOf(')') >= 0 || key.indexOf(':') >= 0 || key.indexOf('<') >= 0) return false;
  const real = dictionary.get(key) || dictionary.get(key.replace(/_/g, ' ')) || dictionary.get(key.replace(/ /g, '_'));
  if (!real) { stats.unknownTags++; if (stats.unknownSamples.length < 25) stats.unknownSamples.push(rawTag); return false; }
  const tag = real.toLowerCase();
  let entry = entries.get(tag);
  if (!entry) {
    entry = { aliases: new Map(), danbooruCategory, treeCategory: null, score: 0 };
    entries.set(tag, entry);
  }
  if (danbooruCategory >= 0 && entry.danbooruCategory < 0) entry.danbooruCategory = danbooruCategory;
  for (const alias of aliases) {
    if (!alias) continue;
    const previous = entry.aliases.get(alias);
    if (previous === undefined || priority < previous) entry.aliases.set(alias, priority);
  }
  return true;
};

// 0) danbooru 分类与热度（决定词条分类与候选排序）
const zhCsv = path.join(TAGS_DIR, 'danbooru.zh_CN_SFW.csv');
const tagsCsv = path.join(TAGS_DIR, 'danbooru.csv');
if (!fs.existsSync(zhCsv)) throw new Error(`找不到中文翻译词库：${zhCsv}\n请用 --tags-dir 指定 a1111-sd-webui-tagcomplete 的 tags 目录。`);
const danbooru = new Map();
if (fs.existsSync(tagsCsv)) {
  for (const line of readLines(tagsCsv)) {
    if (!line.trim()) continue;
    const parts = line.split(',');
    if (!parts[0]) continue;
    danbooru.set(parts[0].trim().toLowerCase(), { category: Number(parts[1]), count: Number(parts[2] || 0) });
  }
}
const danbooruCategoryOf = (tag) => (danbooru.get(tag.trim().toLowerCase()) || {}).category ?? -1;

// 1) 手工同义词表
//    `中文=tag1,tag2`   给这些词条补中文写法（最高优先级）
//    `!中文=tag1,tag2`  从这些词条里**删掉**这个中文写法：社区翻译把通用词挂到了具体词条上
//                       （"背景"→3d_background/backdrop，任何提到背景的句子都会被注入这两个词条）。
const forbidden = new Map();   // 词条 -> 不允许使用的中文写法
const extraFile = path.join(REPO, 'data/prompt-zh-extra.txt');
if (fs.existsSync(extraFile)) {
  for (const raw of readLines(extraFile)) {
    const line = raw.trim();
    if (!line || line.startsWith('#')) continue;
    const at = line.indexOf('=');
    if (at < 0) continue;
    const removing = line.startsWith('!');
    const left = removing ? line.slice(1, at) : line.slice(0, at);
    const words = left.split('/').map(cleanAlias).filter((word) => word && CJK.test(word));
    const tags = line.slice(at + 1).split(',').map((tag) => tag.trim()).filter(Boolean);
    stats.extraLines++;
    if (removing) {
      for (const tag of tags) {
        const key = (dictionary.get(tag.toLowerCase()) || dictionary.get(tag.toLowerCase().replace(/_/g, ' ')) || tag).toLowerCase();
        const set = forbidden.get(key) || new Set();
        for (const word of words) set.add(word);
        forbidden.set(key, set);
      }
      continue;
    }
    for (const tag of tags) addEntry(tag, words, 0, danbooruCategoryOf(tag));
  }
}

// 2) 本机中文分类词库（人工分类，含义最贴近原义）
const usageFile = path.join(REPO, 'data/prompt-usage.json');
const treeCategory = new Map(Object.entries({
  人物: '人物', 服饰: '服装', 表情动作: null, 画面: '画面', 环境: '环境', 场景: '场景', 物品: '物品', 镜头: '镜头', 汉服: '服装', 魔法系: null, 反向提示词: null,
}));
const walkUsage = (node, top) => {
  for (const item of node.tags || []) {
    stats.usageTags++;
    addEntry(item.prompt, aliasesOf(item.meaning || ''), 1, -2);
    const tag = item.prompt.trim().toLowerCase();
    const entry = entries.get(tag);
    if (entry) entry.treeCategory = treeCategory.get(top) ?? null;
  }
  for (const child of node.children || []) walkUsage(child, top);
};
if (fs.existsSync(usageFile)) {
  const usage = JSON.parse(fs.readFileSync(usageFile, 'utf8'));
  for (const category of usage.categories) walkUsage(category, category.name);
}

// 3) danbooru 中文翻译（只要 general / character / copyright，跳过画师与 meta）
let csvRows = 0;
for (const line of readLines(zhCsv)) {
  if (!line.trim()) continue;
  const at = line.indexOf(',');
  if (at < 0) continue;
  const tag = line.slice(0, at).trim().toLowerCase();
  const meaning = line.slice(at + 1).trim();
  if (!meaning) continue;
  const meta = danbooru.get(tag) || { category: -1, count: 0 };
  // 只收 danbooru 明确分类过的画面词条：画师名（1）、meta（5）和本地无法分类的条目（-1）不进画面词库。
  if (meta.category !== 0 && meta.category !== 3 && meta.category !== 4) continue;
  csvRows++;
  if (addEntry(tag, aliasesOf(meaning), 2, meta.category)) stats.csvTags++;
  const entry = entries.get(tag);
  if (entry) entry.score = meta.count;
}

// 用法标注：每个词条除了"词意"（中文写法）之外，还标注"什么需求下使用"和注意事项。
// 模板按分类生成，另外对已知会误用的词条给出更精确的说明（见 data/prompt-zh-use-notes.txt）。
const FLAG_PATTERNS = [
  // 按词条的分词（下划线分开）匹配，避免 chikane 被 chikan 误判成成人向
  ['成人向', /(^|_)(sex|sexual_intercourse|anal|vaginal|penis|pussy|nipple|breasts?|areola|cum|semen|ejaculation|orgasm|masturbation|fellatio|handjob|paizuri|footjob|cunnilingus|anilingus|fingering|penetration|rape|molestation|chikan|groping|bondage|shibari|restrained|nude|naked|topless|bottomless|panties|undressing|erection|aroused|hentai|lewd|upskirt|voyeurism|bukkake|creampie|futanari)(_|$)/i],
  ['画质', /^(masterpiece|best_quality|highres|absurdres|high_resolution|ultra_detailed|detailed|quality|8k|4k|official_art|very_aesthetic|perfect_anatomy|clean_outlines)$|quality|highres|resolution/i],
  ['画风', /(oil_painting|watercolor|sketch|lineart|line_art|cel_shading|flat_color|pixel_art|ukiyo-e|impressionism|realistic|photorealistic|anime_style|art_style|watercolor_effect|traditional_media|monochrome|greyscale|sepia|retro|vintage|cyberpunk|steampunk)/i],
  ['角色', /./],       // 角色/作品分类专用，见下方 category 判断
  ['构图', /(^|_)(pov|from_above|from_below|from_behind|from_side|close-up|portrait|cowboy_shot|full_body|upper_body|lower_body|wide_shot|medium_shot|dutch_angle|depth_of_field|blurry|bokeh|silhouette|zoomed|composition|perspective|multiple_views|split_screen|comic|storyboard|inset)/],
];
const WHEN_TEMPLATE = {
  人物: (names) => `要求画面里出现「${names}」这类人物或外貌特征时`,
  角色: () => '只在用户点名该角色时（角色词条不要自己加）',
  作品: () => '只在用户点名该作品时（作品词条不要自己加）',
  表情: (names) => `要求「${names}」这类表情时`,
  动作: (names) => `要求「${names}」这个动作/行为时`,
  姿势: (names) => `要求「${names}」这个姿势或体位时`,
  服装: (names) => `要求角色穿「${names}」时`,
  场景: (names) => `要求场景是「${names}」时`,
  环境: (names) => `要求天气、时段或自然环境是「${names}」时`,
  物品: (names) => `要求画面里出现「${names}」时`,
  镜头: (names) => `要求用「${names}」这种取景或视角时`,
  画面: (names) => `要求画质或画风是「${names}」时（用户没提画质/画风就不要加）`,
  其他: (names) => `用户明确提到「${names}」时`,
};
/** 逐条覆盖：这里写的词条用专门说明（人工核对过，优先于模板）。 */
const useNotes = new Map();
const notesFile = path.join(REPO, 'data/prompt-zh-use-notes.txt');
if (fs.existsSync(notesFile)) {
  for (const raw of readLines(notesFile)) {
    const line = raw.trim();
    if (!line || line.startsWith('#')) continue;
    const at = line.indexOf('=');
    if (at < 0) continue;
    for (const tag of line.slice(0, at).split(',').map((value) => value.trim()).filter(Boolean))
      useNotes.set(tag.toLowerCase(), line.slice(at + 1).trim());
  }
}

// 一个中文写法只留给最合适的那个词条：
//   手工表（优先级 0）> 分类词库（1）> 社区翻译（2）；同一档里手工/分类的全部保留（"性交"要同时给 sex/vaginal/hetero），
//   社区翻译这一档只留给最热门的那个（"内裤"必须归 panties，不能同时挂到 smelling_underwear 上）。
const claims = new Map();
for (const [tag, entry] of entries)
  for (const [alias, priority] of entry.aliases) {
    if (!claims.has(alias)) claims.set(alias, []);
    claims.get(alias).push({ tag, priority, rank: entry.score || 0 });
  }
const owner = new Map();
let sharedAliases = 0, droppedShared = 0;
for (const [alias, list] of claims) {
  if (list.length > 1) sharedAliases++;
  const best = Math.min(...list.map((item) => item.priority));
  let keep;
  if (best <= 1) keep = list.filter((item) => item.priority === best);
  else keep = [list.reduce((a, b) => (b.rank > a.rank || (b.rank === a.rank && b.tag < a.tag) ? b : a))];
  if (list.length > keep.length) droppedShared += list.length - keep.length;
  owner.set(alias, new Set(keep.map((item) => item.tag)));
}

// 汇总输出
const categories = CATEGORIES.map(() => 0);
const rows = [];
let aliasCount = 0;
let blockedAliases = 0;
for (const [tag, entry] of [...entries.entries()].sort((a, b) => a[0] < b[0] ? -1 : 1)) {
  const blocked = forbidden.get(tag);
  let ordered = [...entry.aliases.entries()].sort((a, b) => a[1] - b[1] || a[0].localeCompare(b[0], 'zh')).map(([alias]) => alias);
  if (blocked && blocked.size) {
    const before = ordered.length;
    // 只摘社区机翻挂上来的写法（优先级 2）：手工表与人工分类词库里的写法是核对过的，
    // 自动审查或 ! 行都不能把它们删掉（实测审查误判"分镜"不是 multiple_views 的说法）。
    ordered = ordered.filter((alias) => !(blocked.has(alias) && entry.aliases.get(alias) >= 2));
    blockedAliases += before - ordered.length;
  }
  ordered = ordered.filter((alias) => owner.get(alias).has(tag)).slice(0, 12);
  if (!ordered.length) continue;
  let category = classify(tag, entry.danbooruCategory, ordered);
  if (category === '其他' && entry.treeCategory) category = entry.treeCategory;
  const index = CATEGORY_INDEX.get(category);
  categories[index]++;
  aliasCount += ordered.length;
  const sample = ordered.slice(0, 3).join('/');
  const flags = [];
  for (const [name, pattern] of FLAG_PATTERNS) {
    if (name === '角色') { if (category === '角色' || category === '作品') flags.push('点名才用'); continue; }
    if (pattern.test(tag)) flags.push(name);
  }
  rows.push([tag, ordered.join('|'), index, entry.score, flags.join(',')]);
}
const byCategory = {};
CATEGORIES.forEach((name, index) => { if (categories[index]) byCategory[name] = categories[index]; });
const payload = {
  version: 1,
  source: '本机 Prompt All-in-One 中文分类词库 + a1111-sd-webui-tagcomplete 中文翻译 + 手工同义词表',
  generated_at: new Date().toISOString().slice(0, 10),
  note: '中文含义来自本机词库与社区翻译（含机翻），仅用于把中文说法对应到标准词条；所有词条均已核对存在于 data/prompt-tags.txt。',
  dictionary: { file: 'data/prompt-tags.txt', tags: dictionaryTags, sha256: sha256(path.join(REPO, 'data/prompt-tags.txt')) },
  sources: [
    { file: 'data/prompt-zh-extra.txt', lines: stats.extraLines, sha256: fs.existsSync(extraFile) ? sha256(extraFile) : '' },
    { file: 'data/prompt-usage.json', tags: stats.usageTags, sha256: fs.existsSync(usageFile) ? sha256(usageFile) : '' },
    { file: sourcePath(zhCsv), rows: csvRows, sha256: sha256(zhCsv) },
    { file: sourcePath(tagsCsv), rows: danbooru.size, sha256: fs.existsSync(tagsCsv) ? sha256(tagsCsv) : '' },
  ],
  counts: { entries: rows.length, aliases: aliasCount, by_category: byCategory },
  categories: CATEGORIES,
  entries: rows,
};
const out = path.join(REPO, 'data/prompt-zh-tags.json');
const text = JSON.stringify(payload);
if (checkOnly) {
  const previous = fs.existsSync(out) ? fs.readFileSync(out, 'utf8') : '';
  const previousBody = previous.replace(/"generated_at":"[^"]*"/, '');
  console.log(`重新生成结果：${rows.length} 条词条 / ${aliasCount} 个中文写法`);
  console.log(previousBody === text.replace(/"generated_at":"[^"]*"/, '') ? '与现有 data/prompt-zh-tags.json 一致。' : '与现有 data/prompt-zh-tags.json 不一致（来源或手工表已变化）。');
  process.exit(0);
}
fs.writeFileSync(out, text);
console.log(`已写入 ${out}`);
console.log(`词条 ${rows.length} 条，中文写法 ${aliasCount} 个`);
if (blockedAliases) console.log(`按别名纠正移除 ${blockedAliases} 个过宽的中文写法（!中文=词条）`);
console.log(`一词多挂清理：${sharedAliases} 个写法被多个词条共用，移除 ${droppedShared} 处挂接（只留最合适的词条）`);
console.log('分类分布：' + Object.entries(byCategory).map(([name, count]) => `${name} ${count}`).join('，'));
console.log(`来源：手工表 ${stats.extraLines} 行，本机分类词库 ${stats.usageTags} 条，中文翻译 ${csvRows} 行（命中标准词库 ${stats.csvTags} 条）`);
if (stats.unknownTags) console.log(`词库外词条 ${stats.unknownTags} 个（已丢弃），示例：${stats.unknownSamples.join(', ')}`);
console.log(`文件大小 ${(fs.statSync(out).size / 1048576).toFixed(2)} MB`);
