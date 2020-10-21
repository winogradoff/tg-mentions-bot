FROM python:3.8

RUN pip install -r requirements.txt

RUN mkdir /app
ADD . /app
WORKDIR /app

CMD python /app/tg-mentions-bot.py
