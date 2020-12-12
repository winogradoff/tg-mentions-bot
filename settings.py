import os

BOT_TOKEN = os.getenv('BOT_TOKEN')
if not BOT_TOKEN:
    print('Environment variable BOT_TOKEN must be set')
    quit()

WEBHOOK_ENABLED = os.getenv('WEBHOOK_ENABLED')
WEBHOOK_ENABLED = WEBHOOK_ENABLED and int(WEBHOOK_ENABLED) == 1

if WEBHOOK_ENABLED:
    HEROKU_APP_NAME = os.getenv('HEROKU_APP_NAME')
    WEBHOOK_HOST = f'https://{HEROKU_APP_NAME}.herokuapp.com'
    WEBHOOK_PATH = f'/webhook/{BOT_TOKEN}'
    WEBHOOK_URL = f'{WEBHOOK_HOST}{WEBHOOK_PATH}'
    WEBAPP_HOST = '0.0.0.0'
    WEBAPP_PORT = int(os.getenv('PORT'))
