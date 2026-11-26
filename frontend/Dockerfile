# The frontend is plain HTML/CSS/JS (no build step) — just ship it behind nginx,
# which also reverse-proxies /api/* to the backend container so the browser only
# ever talks to a single, same-origin host (no CORS in production).
FROM nginx:alpine
COPY . /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
