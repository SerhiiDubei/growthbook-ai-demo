-- Remove query string and fragment from page_url in dom_inventory_latest
-- so all existing records match the normalized URL format used going forward.
UPDATE dom_inventory_latest
SET page_url = REGEXP_REPLACE(page_url, '[?#].*$', '')
WHERE page_url ~ '[?#]';
