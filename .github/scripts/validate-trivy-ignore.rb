#!/usr/bin/env ruby
# frozen_string_literal: true

require "date"
require "yaml"

path = ARGV.fetch(0, ".trivyignore.yaml")
document = YAML.safe_load(File.read(path), permitted_classes: [Date], aliases: true) || {}
entries = document.fetch("vulnerabilities", [])
errors = []
seen = {}
allowed_keys = %w[id paths purls statement expired_at].freeze

entries.each_with_index do |entry, index|
  label = "#{path}: vulnerabilities[#{index}]"
  unless entry.is_a?(Hash)
    errors << "#{label} must be a mapping"
    next
  end

  unknown_keys = entry.keys - allowed_keys
  errors << "#{label} has unknown keys: #{unknown_keys.join(', ')}" unless unknown_keys.empty?

  id = entry["id"].to_s
  errors << "#{label} id must be a CVE" unless id.match?(/\ACVE-\d{4}-\d{4,}\z/)
  errors << "#{label} duplicates #{id}" if seen[id]
  seen[id] = true

  statement = entry["statement"].to_s.strip
  errors << "#{label} requires a non-empty statement" if statement.empty?

  paths = entry["paths"]
  purls = entry["purls"]
  unless (paths.is_a?(Array) && !paths.empty?) || (purls.is_a?(Array) && !purls.empty?)
    errors << "#{label} must be scoped by paths or purls"
  end

  begin
    expiry = Date.parse(entry.fetch("expired_at").to_s)
    errors << "#{label} expired_at is in the past" if expiry < Date.today
    errors << "#{label} expired_at exceeds 90 days" if expiry > Date.today + 90
  rescue KeyError, Date::Error
    errors << "#{label} requires expired_at in YYYY-MM-DD format"
  end
end

if errors.any?
  warn errors.join("\n")
  exit 1
end

puts "Validated #{entries.length} scoped Trivy suppression(s)."
