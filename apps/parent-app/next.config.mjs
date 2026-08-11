/** @type {import('next').NextConfig} */
const nextConfig = {
  reactStrictMode: true,
  transpilePackages: ["@schoolsoft/api-client"],
  // Capacitor wraps a static bundle: `next build` emits out/, which capacitor.config.ts
  // points at as webDir. Trailing slashes keep route paths resolvable as file:// dirs.
  output: "export",
  trailingSlash: true,
};

export default nextConfig;
