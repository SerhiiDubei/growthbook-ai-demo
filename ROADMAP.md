# Roadmap

## ✅ Done

- DOM bridge (`ai-bridge.js`) з інвентаризацією, трекінгом, A/B застосуванням
- AI агент з RecipeTools (swap, reorder, text, style, html, attr, image, class, hide)
- Lifecycle експериментів (Draft → Active → Finished/Failed)
- Синхронізація DB ↔ GrowthBook (одностороння, DB → GB)
- Розширена тестова сторінка (`index.html`) з 9 блоками
- Глобальний CORS — зовнішні сайти можуть підключати bridge
- Підготовка до деплою на DigitalOcean (`.env.example`, `application-prod.yml`, docker-compose cleanup)

---

## 🔜 Next: DigitalOcean Deploy

**Ціль:** робоча демка на DO куди можна інʼєктувати bridge на зовнішній сайт.

**Кроки:**
1. Створити Droplet (рекомендовано 2-4GB RAM, Docker pre-installed)
2. `git clone` репо на Droplet
3. Заповнити `.env` (скопіювати з `.env.example`)
4. `docker compose up -d --build`
5. Пройти GrowthBook initial setup → отримати `GB_ADMIN_TOKEN` і `GB_CLIENT_KEY_PUBLIC`
6. Оновити `.env`, перезапустити `docker compose up -d`
7. Налаштувати DO Firewall: відкрити 8080, 3000; закрити 3100 зовні (або через SSH tunnel)

**Сніпет для зовнішнього сайту:**
```html
<script async
  src="https://cdn.jsdelivr.net/npm/@growthbook/growthbook/dist/bundles/auto.min.js"
  data-client-key="gbpk_ТВІЙ_КЛЮЧ_З_DO"
  data-api-host="http://YOUR_DO_IP:3100"
></script>
<script src="http://YOUR_DO_IP:8080/ai-bridge.js"></script>
```

---

## 🗺 Multi-tenant (SaaS архітектура)

**Ціль:** кожен клієнт має свій ізольований GrowthBook проект і SDK ключ.

### Таблиця `sites`

```sql
CREATE TABLE sites (
    id              BIGSERIAL PRIMARY KEY,
    site_id         VARCHAR(200) UNIQUE NOT NULL, -- 'myshop_com'
    origin          VARCHAR(300) NOT NULL,         -- 'https://myshop.com'
    gb_project_id   VARCHAR(100),                  -- 'prj_abc123' в GrowthBook
    gb_sdk_key      VARCHAR(200),                  -- SDK ключ для браузера клієнта
    owner_name      VARCHAR(200),
    plan            VARCHAR(50) DEFAULT 'trial',
    status          VARCHAR(50) DEFAULT 'available', -- available / assigned
    created_at      TIMESTAMP DEFAULT now(),
    updated_at      TIMESTAMP DEFAULT now()
);
```

### Пул проектів (pre-generation)

- Cron/скрипт заздалегідь створює N проектів в GrowthBook через Admin API:
  - `POST /api/v1/projects` → отримуємо `gb_project_id`
  - `POST /api/v1/sdk-connections` → отримуємо `gb_sdk_key`
  - Зберігаємо в `sites` зі `status='available'`
- При підключенні нового клієнта — видаємо перший `available` запис, міняємо `status='assigned'`
- Реєстрація клієнта миттєва, без затримки на GB API calls

### Зміни в коді

| Компонент | Зміна |
|---|---|
| `SiteRegistryService` | новий сервіс: `getOrCreate(siteId, origin)` |
| `DomInventoryService` | при новому origin — реєструє сайт |
| `GbAdminService` | приймає `gb_project_id` з `Site` замість `@Value` |
| `bridge.js` | повертає правильний `gb_sdk_key` для кожного `siteId` |
| Новий endpoint | `GET /api/sites/{siteId}/config` → повертає sdk_key для клієнта |

### Що залишається глобальним (один на всіх)

- `GB_ADMIN_TOKEN` — тільки на бекенді, клієнти не бачать
- `OPENAI_API_KEY` — тільки на бекенді

---

## 🔐 Безпека (TODO перед production)

- [ ] Basic Auth або API Key на `/api/**` (захист від зловживання OpenAI бюджетом)
- [ ] Закрити порт 3100 (GrowthBook Admin API) на DO Firewall
- [ ] Прибрати дефолтні значення для `GB_ADMIN_TOKEN` і `OPENAI_API_KEY` в `application.yml`
- [ ] MongoDB авторизація (username/password)
- [ ] Rate limiting на `/api/ai/**`
