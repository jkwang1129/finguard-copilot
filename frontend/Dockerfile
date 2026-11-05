FROM node:24.14.1-alpine@sha256:8510330d3eb72c804231a834b1a8ebb55cb3796c3e4431297a24d246b8add4d5 AS build
WORKDIR /build
COPY package.json package-lock.json ./
RUN --mount=type=cache,target=/root/.npm npm ci --no-audit --no-fund
COPY . .
ARG VITE_CORE_REVIEW_URL=http://127.0.0.1:18080/demo/index.html
RUN npm run build

FROM ghcr.io/nginx/nginx-unprivileged:1.30-alpine@sha256:44e74ca483234093081138b6d7b6f561d55557756a2deefc3a5056148defeb99
USER root
RUN apk add --no-cache libexpat=2.10.0-r0
USER 101
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /build/dist /usr/share/nginx/html
EXPOSE 8080
